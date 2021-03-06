/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package org.apache.zookeeper.server.quorum;

import org.apache.zookeeper.jmx.MBeanRegistry;
import org.apache.zookeeper.server.ZooKeeperThread;
import org.apache.zookeeper.server.quorum.QuorumCnxManager.Message;
import org.apache.zookeeper.server.quorum.QuorumPeer.LearnerType;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.QuorumPeer.ServerState;
import org.apache.zookeeper.server.util.ZxidUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;


/**
 * Implementation of leader election using TCP. It uses an object of the class
 * QuorumCnxManager to manage connections. Otherwise, the algorithm is push-based
 * as with the other UDP implementations.
 * <p>
 * There are a few parameters that can be tuned to change its behavior. First,
 * finalizeWait determines the amount of time to wait until deciding upon a leader.
 * This is part of the leader election algorithm.
 */


public class FastLeaderElection implements Election {
    private static final Logger LOG = LoggerFactory.getLogger(FastLeaderElection.class);

    /**
     * Determine how much time a process has to wait
     * once it believes that it has reached the end of
     * leader election.
     */
    final static int finalizeWait = 200;


    /**
     * Upper bound on the amount of time between two consecutive
     * notification checks. This impacts the amount of time to get
     * the system up again after long partitions. Currently 60 seconds.
     */

    final static int maxNotificationInterval = 60000;

    /**
     * Connection manager. Fast leader election uses TCP for
     * communication between peers, and QuorumCnxManager manages
     * such connections.
     */

    QuorumCnxManager manager;


    /**
     * Notifications are messages that let other peers know that
     * a given peer has changed its vote, either because it has
     * joined leader election or because it learned of another
     * peer with higher zxid or same zxid and higher server id
     */

    static public class Notification {
        /*
         * Format version, introduced in 3.4.6
         */

        public final static int CURRENTVERSION = 0x1;
        int version;

        /*
         * Proposed leader
         */
        long leader;

        /*
         * zxid of the proposed leader
         */
        long zxid;

        /*
         * Epoch
         */
        long electionEpoch;

        /*
         * current state of sender
         */
        QuorumPeer.ServerState state;

        /*
         * Address of sender
         */
        long sid;

        /*
         * epoch of the proposed leader
         */
        long peerEpoch;

        @Override
        public String toString() {
            return new String(Long.toHexString(version) + " (message format version), "
                    + leader + " (n.leader), 0x"
                    + Long.toHexString(zxid) + " (n.zxid), 0x"
                    + Long.toHexString(electionEpoch) + " (n.round), " + state
                    + " (n.state), " + sid + " (n.sid), 0x"
                    + Long.toHexString(peerEpoch) + " (n.peerEpoch) ");
        }
    }

    static ByteBuffer buildMsg(int state,
                               long leader,
                               long zxid,
                               long electionEpoch,
                               long epoch) {
        byte requestBytes[] = new byte[40];
        ByteBuffer requestBuffer = ByteBuffer.wrap(requestBytes);

        /*
         * Building notification packet to send 
         */

        requestBuffer.clear();
        requestBuffer.putInt(state);
        requestBuffer.putLong(leader);
        requestBuffer.putLong(zxid);
        requestBuffer.putLong(electionEpoch);
        requestBuffer.putLong(epoch);
        requestBuffer.putInt(Notification.CURRENTVERSION);

        return requestBuffer;
    }

    /**
     * Messages that a peer wants to send to other peers.
     * These messages can be both Notifications and Acks
     * of reception of notification.
     */
    static public class ToSend {
        static enum mType {crequest, challenge, notification, ack}

        ToSend(mType type,
               long leader,
               long zxid,
               long electionEpoch,
               ServerState state,
               long sid,
               long peerEpoch) {

            this.leader = leader;
            this.zxid = zxid;
            this.electionEpoch = electionEpoch;
            this.state = state;
            this.sid = sid;
            this.peerEpoch = peerEpoch;
        }

        /*
         * Proposed leader in the case of notification
         */
        long leader;

        /*
         * id contains the tag for acks, and zxid for notifications
         */
        long zxid;

        /*
         * Epoch
         */
        long electionEpoch;

        /*
         * Current state;
         */
        QuorumPeer.ServerState state;

        /*
         * Address of recipient
         */
        long sid;

        /*
         * Leader epoch
         */
        long peerEpoch;
    }

    // 待发送
    LinkedBlockingQueue<ToSend> sendqueue;
    // 已接收
    LinkedBlockingQueue<Notification> recvqueue;

    /**
     * Multi-threaded implementation of message handler. Messenger
     * implements two sub-classes: WorkReceiver and  WorkSender. The
     * functionality of each is obvious from the name. Each of these
     * spawns a new thread.
     * <p>
     * 消息接受发送者（信使）
     */

    protected class Messenger {

        /**
         * 一个选举消息的投递流程
         * 1. QuorumCnxManager 启动Listener，监听来自各个服务器的请求
         * 2. 其他的服务器和本机的QuorumCnxManager创建连接之后，QuorumCnxManager会启动RecvWorker等待接受消息
         * 3. RecvWorker 接受到消息之后，封装未Message，并保持到 recvQueue 中
         * 4. 选举线程即本法发则一直轮询recvQueue扥带消息到来，获取进行后续的处理
         * <p>
         * Receives messages from instance of QuorumCnxManager on
         * method run(), and processes such messages.
         * 接收
         */
        class WorkerReceiver extends ZooKeeperThread {
            volatile boolean stop;
            QuorumCnxManager manager;

            WorkerReceiver(QuorumCnxManager manager) {
                super("WorkerReceiver");
                this.stop = false;
                this.manager = manager;
            }

            public void run() {

                Message response;
                while (!stop) {
                    // Sleeps on receive
                    try {
                        //从 QuorumCnxManager 中获取到响应
                        response = manager.pollRecvQueue(3000, TimeUnit.MILLISECONDS);
                        if (response == null) continue;

                        /*
                         * If it is from an observer, respond right away.
                         * Note that the following predicate assumes that
                         * if a server is not a follower, then it must be
                         * an observer. If we ever have any other type of
                         * learner in the future, we'll have to change the
                         * way we check for observers.
                         */
                        if (!self.getVotingView().containsKey(response.sid)) {
                            // 发送消息的服务器，没有投票权
                            LOG.debug("来自Observer {" + response.sid + "} 的消息");
                            Vote current = self.getCurrentVote();
                            ToSend notmsg = new ToSend(ToSend.mType.notification,
                                    current.getId(),
                                    current.getZxid(),
                                    logicalclock,
                                    self.getPeerState(),
                                    response.sid,
                                    current.getPeerEpoch());

                            sendqueue.offer(notmsg);
                        } else {
                            // 收到其他的SERVER发送过来的消息

                            // Receive new message
                            if (LOG.isDebugEnabled()) {
//                                LOG.debug("Receive new notification message. My id = "
//                                        + self.getId());
                                LOG.debug("收到新的通知消息从 sid = {" + response.sid + "} 发出，本机 sid = {" + self.getId() + "}");
                            }

                            /*
                             * We check for 28 bytes for backward compatibility
                             * 检查是否能够向后兼容
                             */
                            if (response.buffer.capacity() < 28) {
                                LOG.error("Got a short response: "
                                        + response.buffer.capacity());
                                continue;
                            }
                            //是否使用向后兼容模式
                            boolean backCompatibility = (response.buffer.capacity() == 28);
                            response.buffer.clear();

                            // Instantiate Notification and set its attributes
                            // 实例化收到的通知
                            Notification n = new Notification();

                            // State of peer that sent this message
                            QuorumPeer.ServerState ackstate = QuorumPeer.ServerState.LOOKING;
                            // 响应机器的状态
                            switch (response.buffer.getInt()) {
                                case 0:
                                    ackstate = QuorumPeer.ServerState.LOOKING;
                                    break;
                                case 1:
                                    ackstate = QuorumPeer.ServerState.FOLLOWING;
                                    break;
                                case 2:
                                    ackstate = QuorumPeer.ServerState.LEADING;
                                    break;
                                case 3:
                                    ackstate = QuorumPeer.ServerState.OBSERVING;
                                    break;
                                default:
                                    continue;
                            }

                            n.leader = response.buffer.getLong();
                            n.zxid = response.buffer.getLong();
                            n.electionEpoch = response.buffer.getLong();
                            n.state = ackstate;
                            n.sid = response.sid;
                            if (!backCompatibility) {
                                // 正常模式
                                n.peerEpoch = response.buffer.getLong();
                            } else {
                                // 使用向后兼容模式
                                if (LOG.isInfoEnabled()) {
                                    LOG.info("Backward compatibility mode, server id=" + n.sid);
                                }
                                n.peerEpoch = ZxidUtils.getEpochFromZxid(n.zxid);
                            }

                            /*
                             * Version added in 3.4.6
                             */

                            n.version = (response.buffer.remaining() >= 4) ?
                                    response.buffer.getInt() : 0x0;

                            /*
                             * Print notification info
                             */
                            if (LOG.isInfoEnabled()) {
                                printNotification(n);
                            }

                            /**
                             * If this server is looking, then send proposed leader
                             * 如果本机处于LOOKING状态
                             */

                            if (self.getPeerState() == QuorumPeer.ServerState.LOOKING) {
                                // 本机处于LOOKING阶段
                                // 存入已接受通知列表中
                                recvqueue.offer(n);
                                /*
                                 * Send a notification back if the peer that sent this
                                 * message is also looking and its logical clock is
                                 * lagging behind.
                                 */
                                if ((ackstate == QuorumPeer.ServerState.LOOKING)
                                        && (n.electionEpoch < logicalclock)) {
                                    LOG.debug("收到的通知状态为 " + ackstate
                                            + "{LOOKING}，并且投票纪元小于本机的逻辑时钟，继续发送自己的投票");
                                    Vote v = getVote();
                                    ToSend notmsg = new ToSend(ToSend.mType.notification,
                                            v.getId(),
                                            v.getZxid(),
                                            logicalclock,
                                            self.getPeerState(),
                                            response.sid,
                                            v.getPeerEpoch());
                                    sendqueue.offer(notmsg);
                                }
                            } else {
                                /**
                                 * 本机没有处于LOOKING阶段，但是有返回响应其正在 LOOKING，
                                 * 可能自己是新加入集群中，或者对方是新加入集群中
                                 * If this server is not looking, but the one that sent the ack
                                 * is looking, then send back what it believes to be the leader.
                                 */
                                Vote current = self.getCurrentVote();
                                if (ackstate == QuorumPeer.ServerState.LOOKING) {
                                    if (LOG.isDebugEnabled()) {
//                                        LOG.debug("Sending new notification. My id =  " +
//                                                self.getId() + " recipient=" +
//                                                response.sid + " zxid=0x" +
//                                                Long.toHexString(current.getZxid()) +
//                                                " leader=" + current.getId());
                                        LOG.debug("发送新的通知. 本机 sid 是 =  " +
                                                self.getId() + " 接受者 sid 是=" +
                                                response.sid + " 我的 zxid=0x" +
                                                Long.toHexString(current.getZxid()) +
                                                " 我认为的 leader=" + current.getId());
                                    }

                                    ToSend notmsg;
                                    // 构建要发送的通知，实际上是自己当前的投票信息
                                    if (n.version > 0x0) {
                                        notmsg = new ToSend(
                                                ToSend.mType.notification,
                                                current.getId(),
                                                current.getZxid(),
                                                current.getElectionEpoch(),
                                                self.getPeerState(),
                                                response.sid,
                                                current.getPeerEpoch());

                                    } else {
                                        // 兼容在 3.4.6 之前的版本
                                        Vote bcVote = self.getBCVote();
                                        notmsg = new ToSend(
                                                ToSend.mType.notification,
                                                bcVote.getId(),
                                                bcVote.getZxid(),
                                                bcVote.getElectionEpoch(),
                                                self.getPeerState(),
                                                response.sid,
                                                bcVote.getPeerEpoch());
                                    }
                                    sendqueue.offer(notmsg);
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        System.out.println("Interrupted Exception while waiting for new message" +
                                e.toString());
                    }
                }
                LOG.info("WorkerReceiver is down");
            }
        }


        /**
         * 发送
         * This worker simply dequeues a message to send and
         * and queues it on the manager's queue.
         */

        class WorkerSender extends ZooKeeperThread {
            volatile boolean stop;
            // 通过Server之间创建的连接发送消息
            QuorumCnxManager manager;

            WorkerSender(QuorumCnxManager manager) {
                super("WorkerSender");
                this.stop = false;
                this.manager = manager;
            }

            public void run() {
                while (!stop) {
                    try {
                        ToSend m = sendqueue.poll(3000, TimeUnit.MILLISECONDS);
                        if (m == null) continue;

                        process(m);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                LOG.info("WorkerSender is down");
            }

            /**
             * Called by run() once there is a new message to send.
             *
             * @param m message to send
             */
            void process(ToSend m) {
                ByteBuffer requestBuffer = buildMsg(m.state.ordinal(),
                        m.leader,
                        m.zxid,
                        m.electionEpoch,
                        m.peerEpoch);
                manager.toSend(m.sid, requestBuffer);
            }
        }

        /**
         * Test if both send and receive queues are empty.
         */
        public boolean queueEmpty() {
            return (sendqueue.isEmpty() || recvqueue.isEmpty());
        }


        WorkerSender ws;
        WorkerReceiver wr;

        /**
         * Constructor of class Messenger.
         *
         * @param manager Connection manager
         */
        Messenger(QuorumCnxManager manager) {

            // 消息发送线程
            this.ws = new WorkerSender(manager);

            Thread t = new Thread(this.ws,
                    "WorkerSender[myid=" + self.getId() + "]");
            t.setDaemon(true);
            t.start();

            // 消息接收线程
            this.wr = new WorkerReceiver(manager);

            t = new Thread(this.wr,
                    "WorkerReceiver[myid=" + self.getId() + "]");
            t.setDaemon(true);
            t.start();
        }

        /**
         * Stops instances of WorkerSender and WorkerReceiver
         */
        void halt() {
            this.ws.stop = true;
            this.wr.stop = true;
        }

    }

    QuorumPeer self;
    Messenger messenger;

    //用于识别每一轮投票选举，有效的投票需要在同一轮投票中，每一次发起一次选举，都会+1
    // 吐槽一下，这个真是一个匪夷所思的命名，可以叫做electionRound这样显然更容易理解一点
    volatile long logicalclock; /* Election instance */
    /**
     * 提案leader id
     */
    long proposedLeader;
    /**
     * zxid
     */
    long proposedZxid;
    /**
     * 选举纪元
     */
    long proposedEpoch;


    /**
     * Returns the current vlue of the logical clock counter
     */
    public long getLogicalClock() {
        return logicalclock;
    }

    /**
     * Constructor of FastLeaderElection. It takes two parameters, one
     * is the QuorumPeer object that instantiated this object, and the other
     * is the connection manager. Such an object should be created only once
     * by each peer during an instance of the ZooKeeper service.
     *
     * @param self    QuorumPeer that created this object
     * @param manager Connection manager
     */
    public FastLeaderElection(QuorumPeer self, QuorumCnxManager manager) {
        this.stop = false;
        this.manager = manager;
        // 初始化之后，启动消息发送和接受的监听
        starter(self, manager);
    }

    /**
     * This method is invoked by the constructor. Because it is a
     * part of the starting procedure of the object that must be on
     * any constructor of this class, it is probably best to keep as
     * a separate method. As we have a single constructor currently,
     * it is not strictly necessary to have it separate.
     *
     * @param self    QuorumPeer that created this object
     * @param manager Connection manager
     */
    private void starter(QuorumPeer self, QuorumCnxManager manager) {
        this.self = self;
        proposedLeader = -1;
        proposedZxid = -1;

        sendqueue = new LinkedBlockingQueue<ToSend>();
        recvqueue = new LinkedBlockingQueue<Notification>();
        this.messenger = new Messenger(manager);
    }

    /**
     * 离开选举，清理接受到的消息
     *
     * @param v
     */
    private void leaveInstance(Vote v) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("About to leave FLE instance: leader="
                    + v.getId() + ", zxid=0x" +
                    Long.toHexString(v.getZxid()) + ", my id=" + self.getId()
                    + ", my state=" + self.getPeerState());
        }
        recvqueue.clear();
    }

    public QuorumCnxManager getCnxManager() {
        return manager;
    }

    volatile boolean stop;

    public void shutdown() {
        stop = true;
        LOG.debug("Shutting down connection manager");
        manager.halt();
        LOG.debug("Shutting down messenger");
        messenger.halt();
        LOG.debug("FLE is down");
    }


    /**
     * 发送投票信息
     * Send notifications to all peers upon a change in our vote
     */
    private void sendNotifications() {
        // 向所有的服务器广播投票
        for (QuorumServer server : self.getVotingView().values()) {
            long sid = server.id;

            /**
             * 要发送的消息
             */
            ToSend notmsg = new ToSend(ToSend.mType.notification,
                    proposedLeader,
                    proposedZxid,
                    logicalclock,
                    QuorumPeer.ServerState.LOOKING,
                    sid,
                    proposedEpoch);
            if (LOG.isDebugEnabled()) {
                LOG.debug("Sending Notification: " + proposedLeader + " (n.leader), 0x" +
                        Long.toHexString(proposedZxid) + " (n.zxid), 0x" + Long.toHexString(logicalclock) +
                        " (n.round), " + sid + " (recipient), " + self.getId() +
                        " (myid), 0x" + Long.toHexString(proposedEpoch) + " (n.peerEpoch)");
            }
            sendqueue.offer(notmsg);
        }
    }


    private void printNotification(Notification n) {
        LOG.info("Notification: " + n.toString()
                + self.getPeerState() + " (my state)");
    }

    /**
     * Check if a pair (server id, zxid) succeeds our
     * current vote.
     * 检查新的投票是否比当前投票更加优先
     * <p>
     * //     * @param id   Server identifier
     * //     * @param zxid Last zxid observed by the issuer of this vote
     */
    protected boolean totalOrderPredicate(long newId, long newZxid, long newEpoch, long curId, long curZxid, long curEpoch) {
        LOG.debug("id: " + newId + ", proposed id: " + curId + ", zxid: 0x" +
                Long.toHexString(newZxid) + ", proposed zxid: 0x" + Long.toHexString(curZxid));
        if (self.getQuorumVerifier().getWeight(newId) == 0) {
            return false;
        }
        
        /*
         * 本质上是需要选出zxid最大的服务器
         * We return true if one of the following three cases hold:
         * 1- New epoch is higher  比较 epoch 大小
         * 2- New epoch is the same as current epoch, but new zxid is higher 比较zxid大小
         * 3- New epoch is the same as current epoch, new zxid is the same
         *  as current zxid, but server id is higher. 比较 server id大小，
         *  因此如果其他状态相等，机器同时启动，会选举出myid最大的机器作为 Leader
         */

        return ((newEpoch > curEpoch) ||
                ((newEpoch == curEpoch) &&
                        ((newZxid > curZxid) || ((newZxid == curZxid) && (newId > curId)))));
    }

    /**
     * Termination predicate. Given a set of votes, determines if
     * have sufficient to declare the end of the election round.
     * <p>
     * 在本轮投票结束后，是否有足够的半数通过的机器
     *
     * @param votes Set of votes
     *              //     * @param l     Identifier of the vote received last
     *              //     * @param zxid  zxid of the the vote received last
     */
    protected boolean termPredicate(
            HashMap<Long, Vote> votes,
            Vote vote) {
        //校验半数通过vote
        HashSet<Long> set = new HashSet<Long>();

        /*
         * First make the views consistent. Sometimes peers will have
         * different zxids for a server depending on timing.
         */
        for (Map.Entry<Long, Vote> entry : votes.entrySet()) {
            if (vote.equals(entry.getValue())) {
                set.add(entry.getKey());
            }
        }

        return self.getQuorumVerifier().containsQuorum(set);
    }

    /**
     * 确认收到的投票中存在leader
     * <p>
     * In the case there is a leader elected, and a quorum supporting
     * this leader, we have to check if the leader has voted and acked
     * that it is leading.  s keep
     * electing over and over a peer that has crashed and it is no
     * longer leading.
     * <p>
     * 已经选择了一个Leader，并且有多数派同意，这边再次校验Leader的状态为LEADING
     * 避免选中的leader出现了crash，导致没有leader被选中。
     *
     * @param votes         set of votes
     * @param leader        leader id
     * @param electionEpoch epoch id
     */
    protected boolean checkLeader(
            HashMap<Long, Vote> votes,
            long leader,
            long electionEpoch) {

        boolean predicate = true;

        /*
         * If everyone else thinks I'm the leader, I must be the leader.
         * The other two checks are just for the case in which I'm not the
         * leader. If I'm not the leader and I haven't received a message
         * from leader stating that it is leading, then predicate is false.
         *
         * 自己是leader，或者votes中有server声明自己是leader
         */

        if (leader != self.getId()) {
            if (votes.get(leader) == null) {
                predicate = false;
            } else if (votes.get(leader).getState() != ServerState.LEADING) {
                predicate = false;
            }
        } else if (logicalclock != electionEpoch) {
            predicate = false;
        }

        return predicate;
    }

    /**
     * 确认outofelection中选举出的leader的有效性
     *
     * This predicate checks that a leader has been elected. It doesn't
     * make a lot of sense without context (check lookForLeader) and it
     * has been separated for testing purposes.
     *
     * @param recv map of received votes
     * @param ooe  map containing out of election votes (LEADING or FOLLOWING)
     * @param n    Notification
     * @return
     */
    protected boolean ooePredicate(HashMap<Long, Vote> recv,
                                   HashMap<Long, Vote> ooe,
                                   Notification n) {
        /**
         * termPredicate 首先判断recv中是否有半数成员对 n 提案达成一致
         * checkLeader 判断ooe中以及该选择出的leader处于LEADING的状态
         */
        return (termPredicate(recv, new Vote(n.version,
                n.leader,
                n.zxid,
                n.electionEpoch,
                n.peerEpoch,
                n.state))
                && checkLeader(ooe, n.leader, n.electionEpoch));

    }

    synchronized void updateProposal(long leader, long zxid, long epoch) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Updating proposal: " + leader + " (newleader), 0x"
                    + Long.toHexString(zxid) + " (newzxid), " + proposedLeader
                    + " (oldleader), 0x" + Long.toHexString(proposedZxid) + " (oldzxid)");
        }
        proposedLeader = leader;
        proposedZxid = zxid;
        proposedEpoch = epoch;
    }

    synchronized Vote getVote() {
        return new Vote(proposedLeader, proposedZxid, proposedEpoch);
    }

    /**
     * A learning state can be either FOLLOWING or OBSERVING.
     * This method simply decides which one depending on the
     * role of the server.
     *
     * @return ServerState
     */
    private ServerState learningState() {
        if (self.getLearnerType() == LearnerType.PARTICIPANT) {
            LOG.debug("I'm a participant: " + self.getId());
            return ServerState.FOLLOWING;
        } else {
            LOG.debug("I'm an observer: " + self.getId());
            return ServerState.OBSERVING;
        }
    }

    /**
     * Returns the initial vote value of server identifier.
     *
     * @return long
     */
    private long getInitId() {
        if (self.getLearnerType() == LearnerType.PARTICIPANT)
            return self.getId();
        else return Long.MIN_VALUE;
    }

    /**
     * Returns initial last logged zxid.
     * <p>
     * 返回当前的最大zxid值（从DataTree中获取）
     *
     * @return long
     */
    private long getInitLastLoggedZxid() {
        if (self.getLearnerType() == LearnerType.PARTICIPANT)
            return self.getLastLoggedZxid();
        else return Long.MIN_VALUE;
    }

    /**
     * Returns the initial vote value of the peer epoch.
     *
     * @return long
     */
    private long getPeerEpoch() {
        if (self.getLearnerType() == LearnerType.PARTICIPANT)
            try {
                return self.getCurrentEpoch();
            } catch (IOException e) {
                RuntimeException re = new RuntimeException(e.getMessage());
                re.setStackTrace(e.getStackTrace());
                throw re;
            }
        else return Long.MIN_VALUE;
    }

    /**
     * 开始新一轮的选举
     * Starts a new round of leader election. Whenever our QuorumPeer
     * changes its state to LOOKING, this method is invoked, and it
     * sends notifications to all other peers.
     */
    public Vote lookForLeader() throws InterruptedException {
        // JMX register
        try {
            self.jmxLeaderElectionBean = new LeaderElectionBean();
            MBeanRegistry.getInstance().register(
                    self.jmxLeaderElectionBean, self.jmxLocalPeerBean);
        } catch (Exception e) {
            LOG.warn("Failed to register with JMX", e);
            self.jmxLeaderElectionBean = null;
        }
        if (self.start_fle == 0) {
            self.start_fle = System.currentTimeMillis();
        }
        try {
            // 收到的投票
            HashMap<Long, Vote> recvset = new HashMap<Long, Vote>();
            // map containing out of election votes (LEADING or FOLLOWING)
            // 维护来自LEADING和FOLLOWING状态的建议，
            // outofelction保存了"非投票时机"(其他server已经确定了LEADING之后,
            // 或者FOLLOWING正常无需投票)接受到的其他server的提议,
            // 检查leader也就是从outoofelection集合中查找自己是否收到了状态为LEADING的server提议.
            HashMap<Long, Vote> outofelection = new HashMap<Long, Vote>();

            int notTimeout = finalizeWait;

            synchronized (this) {
                // 逻辑时间增加
                logicalclock++;
                // 先给自己投票（更新自己的proposalId）
                updateProposal(getInitId(), getInitLastLoggedZxid(), getPeerEpoch());
            }

            LOG.info("New election. My id =  " + self.getId() +
                    ", proposed zxid=0x" + Long.toHexString(proposedZxid));
            sendNotifications();

            /*
             * Loop in which we exchange notifications until we find a leader
             */

            // 循环直到选举出 Leader
            while ((self.getPeerState() == ServerState.LOOKING) &&
                    (!stop)) {
                /*
                 * Remove next notification from queue, times out after 2 times
                 * the termination time
                 * 从接收到的信息中获得投票，并recequeue中删除这个消息
                 */
                Notification n = recvqueue.poll(notTimeout,
                        TimeUnit.MILLISECONDS);

                /*
                 * Sends more notifications if haven't received enough.
                 * Otherwise processes new notification.
                 */
                if (n == null) {
                    // 消息已经投递成功
                    if (manager.haveDelivered()) {
                        // 发送通知
                        sendNotifications();
                    } else {
                        // 消息还没有投递成功，尝试从新和各个Server创建连接
                        manager.connectAll();
                    }

                    /*
                     * Exponential backoff
                     */
                    int tmpTimeOut = notTimeout * 2;
                    notTimeout = (tmpTimeOut < maxNotificationInterval ?
                            tmpTimeOut : maxNotificationInterval);
                    LOG.info("Notification time out: " + notTimeout);
                } else if (self.getVotingView().containsKey(n.sid)) {
                    /**
                     * 收到了来自有选举权的Server的投票
                     *
                     * Only proceed if the vote comes from a replica in the
                     * voting view.
                     */
                    LOG.debug("收到{" + n.state + "}状态的通知");
                    printNotification(n);
                    LOG.debug("##");
                    switch (n.state) {
                        case LOOKING:
                            // 收到的投票状态为 looking
                            // If notification > current, replace and send messages out
                            if (n.electionEpoch > logicalclock) {
                                // 如果n的投票轮次 大于 本机的投票轮次，
                                // 将自己的轮次修改为收到消息轮次
                                logicalclock = n.electionEpoch;
                                // 将之前接受到的投票清除，实际上就是开始新一轮的投票
                                recvset.clear();
                                // 收到消息的优先级是否更高
                                if (totalOrderPredicate(n.leader, n.zxid, n.peerEpoch,
                                        getInitId(), getInitLastLoggedZxid(), getPeerEpoch())) {
                                    // 将自己的投票修改为当前收到server的投票
                                    updateProposal(n.leader, n.zxid, n.peerEpoch);
                                } else {
                                    // 否则再次给自己投票
                                    updateProposal(getInitId(),
                                            getInitLastLoggedZxid(),
                                            getPeerEpoch());
                                }
                                // 发出广播消息
                                sendNotifications();
                            } else if (n.electionEpoch < logicalclock) {
                                // 如果收到的消息小于本机轮次，旧的投票，显然不能覆盖当前的投票
                                // 收到的消息已经没有意义，则直接放弃，获取下一个通知
                                // 在此之前已经有通过sendNotifications()发送本轮次的logicalColock给其他的SERVER
                                if (LOG.isDebugEnabled()) {
                                    LOG.debug("Notification election epoch is smaller than logicalclock. n.electionEpoch = 0x"
                                            + Long.toHexString(n.electionEpoch)
                                            + ", logicalclock=0x" + Long.toHexString(logicalclock));
                                }
                                break;
                            } else if (totalOrderPredicate(n.leader, n.zxid, n.peerEpoch,
                                    proposedLeader, proposedZxid, proposedEpoch)) {
                                // 相同轮次的话，比较后接受
                                updateProposal(n.leader, n.zxid, n.peerEpoch);
                                sendNotifications();
                            }

                            if (LOG.isDebugEnabled()) {
                                LOG.debug("Adding vote: from=" + n.sid +
                                        ", proposed leader=" + n.leader +
                                        ", proposed zxid=0x" + Long.toHexString(n.zxid) +
                                        ", proposed election epoch=0x" + Long.toHexString(n.electionEpoch));
                            }

                            // 接受到的投票， k（sid） ： v（vote）
                            recvset.put(n.sid, new Vote(n.leader, n.zxid, n.electionEpoch, n.peerEpoch));

                            if (termPredicate(recvset,
                                    new Vote(proposedLeader, proposedZxid,
                                            logicalclock, proposedEpoch))) {
                                // 判断是否有节点，收到了半数的投票

                                // Verify if there is any change in the proposed leader
                                while ((n = recvqueue.poll(finalizeWait,
                                        TimeUnit.MILLISECONDS)) != null) {
                                    if (totalOrderPredicate(n.leader, n.zxid, n.peerEpoch,
                                            proposedLeader, proposedZxid, proposedEpoch)) {
                                        // 1. 如果新收到的消息中有比之前消息优先级更高的消息，则跳出循环，意味着这次半数投票无法生效
                                        // 核心的问题在于，zk的选举必须要保证选举出来的leader具有最大的zxid
                                        recvqueue.put(n);
                                        break;
                                    }
                                }

                                /**
                                 * This predicate is true once we don't read any new
                                 * relevant message from the reception queue
                                 * 如果n == null，可知 1. 并未执行
                                 */
                                if (n == null) {
                                    // 修改自己的状态为Leader或者是Leanner
                                    self.setPeerState((proposedLeader == self.getId()) ?
                                            ServerState.LEADING : learningState());

                                    // 最终选票
                                    Vote endVote = new Vote(proposedLeader,
                                            proposedZxid,
                                            logicalclock,
                                            proposedEpoch);
                                    leaveInstance(endVote);
                                    return endVote;
                                }
                                /**
                                 * 如果在执行上面这句的过程中，接收到一个优先级更高的notification，会发生什么事情？为了简单描述，
                                 * 假设这个notification最后被majority的机器接受
                                 * 这样一次执行后，可能会出现不收敛的情况
                                 * 情况1：选举自己为LEADER，则显然，这个LEADER在后面的lead()中无法得到majority机器的NEWLEADER响应，重新进入选举
                                 * 情况2：选举出自己为FOLLOWER，则必然有一个LEADER被选举出来，而且这个LEADER也会出现情况1，无法称为正常的LEADER，因此本机重新进入选举
                                 * finalizeWait时间的加入，减少了出现不收敛的可能性
                                 */
                            }
                            break;
                        // observing不需要加入选举
                        case OBSERVING:
                            LOG.debug("Notification from observer: " + n.sid);
                            break;
                        case FOLLOWING:
                        case LEADING:
                            /**
                             * Consider all notifications from the same epoch
                             * together.
                             */
                            // 是否与当前逻辑时钟不符合的消息,如果是否那么说明在另一个选举过程中已经有了选举结果
                            if (n.electionEpoch == logicalclock) {
                                // 在本次投票选举中，收到的选票
                                recvset.put(n.sid, new Vote(n.leader,
                                        n.zxid,
                                        n.electionEpoch,
                                        n.peerEpoch));
                                // 是否通过这个LEADER
                                if (ooePredicate(recvset, outofelection, n)) {
                                    // 已经通过，修改自己的状态
                                    self.setPeerState((n.leader == self.getId()) ?
                                            ServerState.LEADING : learningState());

                                    Vote endVote = new Vote(n.leader,
                                            n.zxid,
                                            n.electionEpoch,
                                            n.peerEpoch);
                                    leaveInstance(endVote);
                                    return endVote;
                                }
                            }

                            /**
                             * outofelection 记录LEADING和FOLLOWING状态的选票
                             *
                             * Before joining an established ensemble, verify
                             * a majority is following the same leader.
                             */
                            outofelection.put(n.sid, new Vote(n.version,
                                    n.leader,
                                    n.zxid,
                                    n.electionEpoch,
                                    n.peerEpoch,
                                    n.state));
                            //加入一个已经存在的集合，先检查大多数的follower是否有相同的Leader
                            if (ooePredicate(outofelection, outofelection, n)) {
                                synchronized (this) {
                                    logicalclock = n.electionEpoch;
                                    self.setPeerState((n.leader == self.getId()) ?
                                            ServerState.LEADING : learningState());
                                }
                                Vote endVote = new Vote(n.leader,
                                        n.zxid,
                                        n.electionEpoch,
                                        n.peerEpoch);
                                leaveInstance(endVote);
                                return endVote;
                            }
                            break;
                        default:
                            LOG.warn("Notification state unrecognized: {} (n.state), {} (n.sid)",
                                    n.state, n.sid);
                            break;
                    }
                } else {
                    LOG.warn("Ignoring notification from non-cluster member " + n.sid);
                }
            }
            return null;
        } finally {
            try {
                if (self.jmxLeaderElectionBean != null) {
                    MBeanRegistry.getInstance().unregister(
                            self.jmxLeaderElectionBean);
                }
            } catch (Exception e) {
                LOG.warn("Failed to unregister with JMX", e);
            }
            self.jmxLeaderElectionBean = null;
        }
    }
}
