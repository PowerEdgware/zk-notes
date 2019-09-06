##zk版本：3.4.14
### 单机  

** 启动流程 **
* 启动类 ZooKeeperServerMain  
通过ZooKeeperServerMain主方法启动，解析配置，runFromConfig方法启动zk服务。  
* 服务类  ZooKeeperServer 
通过连接工厂：ServerCnxnFactory 启动默认的Nio NIOServerCnxnFactory  
`cnxnFactory.startup(zkServer);` 启动一个2181端口的监听，同时执行当前连接工厂的run方法，接受客户端请求。

** 消息处理链设置 **   
在启动过程中，会启动zkServer `zks.startup()`
同时设置请求的处理连：  ZooKeeperServer.setupRequestProcessors  
处理连调用流程：  
`PrepRequestProcessor->SyncRequestProcessor->FinalRequestProcessor`  
有的处理连是ZookeeperThread线程，内部维护阻塞队列，用到的是生产者消费者模式+责任链模式处理消息。

** 消息的接受与处理 **  

NIOServerCnxnFactory 是一个Runnable线程执行体。接受客户端连接从这里开始。

业务处理略

---
### 集群  

#### 启动类 QuorumPeerMain  
** 1.启动   **  
 首先解析配置，设置暴露的客户端端口2181，设置集群的配置，包括集群节点，选举接口，数据同步接口。  
 `QuorumPeerMain.runFromConfig ->QuorumPeer.start`
 
QuorumPeer.start 分为几个步骤：  
* 启动客户端连接端口服务2181  
其实就是调用`ServerCnxnFactory.start` 启动一个NIO的服务端用来接收zk客户端连接.在leader选举没有结束之前，由于该连接已经暴露完毕，此时  
客户端有连接进来，会因为集群选举未完成而导致客户端被迫关闭。具体判断代码：  
`NIOServerCnxnFactory.run->NIOServerCnxn.doIO->NIOServerCnxn.readPayload->`
此时的NIOServerCnxn未初始化，所以会走到函数：  

```    
  private void readConnectRequest() throws IOException, InterruptedException {
        if (!isZKServerRunning()) {  //zk集群的选举完成后，才会初始化并启动leader/follower server.so isZKServerRunning返回false
            throw new IOException("ZooKeeperServer not running");
        }
        zkServer.processConnectRequest(this, incomingBuffer);
        initialized = true;
    }
```
* 开启leader选举  
QuorumPeer.start 会确定选举算法，并启动一个zk线程，由于QuorumPeer是继承ZookeeperThread的线程，因此启动后执行当前QuorumPeer的run方法，  
开始真正的leader选举过程。


** 2.leader选举  **  
在QuorumPeer的run方法中，一开始集群中每个QuorumPeer节点的都处于寻找leader节点状态。  随后调用：`FastLeaderElection.lookForLeader` 进行选举，  
此过程一直阻塞，直到选出 leader 
* `FastLeaderElection.lookForLeader`选举算法  
第一步：是发送自己的选票给集群所有节点(整个选举过程就是投票，开始的一票投给自己)    
第二步：接受选票，比较选票，大的留下更新当前候选ProposalLeader信息  
第三步：判断收到的票据是否有超出过半的票是候选leader，是则设置当前节点状态，结束选举，否则继续。

* 发送接收消息流程  
FastLeaderElection 内部维护了两个线程：  
`WorkerReceiver`:接收选举投票的消息。  
`WorkerSender`:发送投票消息。  
以上两个发送和接收投票消息的线程类都是借助：`QuorumCnxManager` 实现io消息的收发。  
* 发送投票信息  
把消息发送给集群中所有非observer节点。FastLeaderElection.sendNotifications()  

```
    private void sendNotifications() {
        for (QuorumServer server : self.getVotingView().values()) {
            long sid = server.id;
            ToSend notmsg = new ToSend(ToSend.mType.notification,
                    proposedLeader,
                    proposedZxid,
                    logicalclock.get(),
                    QuorumPeer.ServerState.LOOKING,
                    sid,
                    proposedEpoch);
            if(LOG.isDebugEnabled()){
                LOG.debug("Sending Notification: " + proposedLeader + " (n.leader), 0x"  +
                      Long.toHexString(proposedZxid) + " (n.zxid), 0x" + Long.toHexString(logicalclock.get())  +
                      " (n.round), " + sid + " (recipient), " + self.getId() +
                      " (myid), 0x" + Long.toHexString(proposedEpoch) + " (n.peerEpoch)");
            }
            sendqueue.offer(notmsg);
        }
    }
```
消息发送细节：  
发送的消息：ToSend数据模型和含义:  
proposedLeader: 被推举的leader  
proposedZxid: 被推荐的leader zxid  
electionEpoch： 当前选举轮中 epoch  
sid: 接受消息的serverid  
peerEpoch:被推举的leader epoch  

发送的消息被放入发送队列：`FastLeaderElection.sendqueue`  
上面说到的`WorkerSender` 从 `sendqueue` 获取消息并调用io通信的辅助类：`QuorumCnxManager` 把投票消息发送出去。发给 `ToSend.sid`  

`FastLeaderElection.WorkerSender`调用`QuorumCnxManager.send`发送消息：    
 建立连接： 发送者（当前peer）向接收者（`ToSend.sid`）发起socket连接并进行连接初始化。并首先把自己的sid发送给对方。
 检测自己的sid和接收者的sid大小，确保sid较小的是监听端，sid较大的连接sid较小的peer。  
 连接一旦完毕，在`QuorumCnxManager`中会分别启动该socket连接的两个线程对端到端消息的的读取和处理：  
`QuorumCnxManager.SendWorker`--io通信消息的发送   
`QuorumCnxManager.RecvWorker` --io通信消息的接收  
 
* 接收投票信息  
 上面说到，在集群环境中，选举连接的建立，只有sid较大的去连接sid较小的。投票消息的监听者在：`QuorumCnxManager`的内部类`Listener`中。    
 在`QuorumCnxManager`建立完毕，确定完选举算法的的时候被启动：
 `QuorumPeer.startLeaderElection.createElectionAlgorithm`方法中。 
`QuorumCnxManager`的内部类`Listener` 是继承自 `ZooKeeperThread`的一个线程。启动完毕自然会运行run方法。  

`Listener.run`既是接收投票消息的入口：  
接收连接，处理消息。  

```  
    public void receiveConnection(final Socket sock) {
        DataInputStream din = null;
        try {
            din = new DataInputStream(
                    new BufferedInputStream(sock.getInputStream()));

            handleConnection(sock, din);
        } catch (IOException e) {
            LOG.error("Exception handling connection, addr: {}, closing server connection",
                     sock.getRemoteSocketAddress());
            closeSocket(sock);
        }
    }
```

接收到客户端的连接后，获取客户端的sid，并分别建立该socket连接的发送和接收消息的线程。
`SendWorker` /`RecvWorker`  

`RecvWorker.run`  是真正读取该socket消息的线程。流程如下：  

接收到发送者sid发送的消息，读取发送端投票信息，把投票信息和发送者sid封装成一个消息Message,随后把该消息放入  
`QuorumCnxManager.recvQueue` 阻塞队列中。  
而在`FastLeaderElection.WorkerReceiver` 会调用 `QuorumCnxManager.pollRecvQueue`方法，从阻塞队列获取Message消息。  

回到`FastLeaderElection.WorkerReceiver.run`  
run从上述阻塞队列获取Message消息，并把合法的消息封装成Notification 放入： `FastLeaderElection.recvqueue`队列中。  
该队列正好是发送选票的线程：QuorumPeer.run，它不断从`FastLeaderElection.recvqueue`获取 `Notification` 进行选举选票流程。  

其中：`Notification`数据结构：  

```
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

```

接收到选票以后（也包括自己投票给自己的选票） 开始leader选举的第二步、第三步：  

第二步：PK票据  
1.PK接收到的选票，判断票据合法性，随后比较是否比当前自己保存的候选leader:ProposalLeader信息大，如果是则更新本地ProposalLeader票据为最大leader。
并把最大的票据信息发送出去。  
2.保存接收到的选票信息，保存的信息是：  
` recvset.put(n.sid, new Vote(n.leader, n.zxid, n.electionEpoch, n.peerEpoch));`  
n.sid--对应票据的发送者。  
Vote--发送者发送的票据，也即投给谁的票据。  

第三步： 判断票据  
针对收到的票据集合，判断当前收到的票数中投给准的leader是否超出总票数的一半，如果超出了，则设置当前节点的角色状态 ：  

```
QurorumPeer.setPeerState((proposedLeader == self.getId()) ?
                                        ServerState.LEADING: learningState());
```

是leader/follower/observer ，结束选举并返回leader选票。  

选举结束返回到 QuronumPeer.run，节点设置当前票据是leader票据信息：
` setCurrentVote(makeLEStrategy().lookForLeader());`  
因为选举结束，当前节点的节点状态`peerState`已经是：leader/follower/observer 中的一个，所以下面的流程就是follower/observer连接leader
进行数据同步和对外提供服务。  

** 3.建立通信，同步Leader**  
选举结束后，集群中节点状态由 LOOKING变成 FOLLOWERING/LEADING/OBSERVING其中的一个，建立自身监听服务ZKServer。

* 创建Follower服务  

```
QuorumPeer.makeFollower
  protected Follower makeFollower(FileTxnSnapLog logFactory) throws IOException {
        return new Follower(this, new FollowerZooKeeperServer(logFactory, 
                this,new ZooKeeperServer.BasicDataTreeBuilder(), this.zkDb));
    }
```

* Follower追随Leader
`follower.followLeader()`  
3.1  建立与Leader的通信连接  
3.2  向leader注册自己的信息  
3.3  从leader侧同步数据  
新的leader选出以后，作为follower需要从leader同步最新的数据，同步完毕会设置follower服务端的初始化，构建处理链路等。  
3.4  同步完毕，初始化完毕，开始正常处理客户端请求。  
`follower.followLeader()`  中会循环读取并处理数据  

```
  while (this.isRunning()) {
      readPacket(qp);
      processPacket(qp);
  }
```
上述代码里follower的主要职责是与leader通信进行数据同步，也是接收leader的事务请求，本地事务日志，进行ack.接收leader事务的commit请求，执行事务提交。  
另外一点，接收客户端的查询请求，转发事务请求等。具体接收客户端请求的逻辑在：  
`ServerCnxnFactory`子类的`run`方法中，这里专门接收客户端请求  

follower处理链路：  
`FollowerRequestProcessor->CommitProcessor->FinalRequestProcessor`  
同步链路 ：  
`SyncRequestProcessor->SendAckRequestProcessor`  

* Leader领导集群  
`leader.lead()`这里会进入死循环。  

leader首先启动一个监听服务，用于follower/observer 连接过来做数据同步和proposal通信。  
监听器： `LearnerCnxAcceptor` 用于接收follower连接请求，数据通信。  

启动leader服务：   
构建leader处理链路：`PrepRequestProcessor->ProposalRequestProcessor->CommitProcessor->ToBeAppliedRequestProcessor->FinalRequestProcessor`  
设置Leader服务器状态为：`State.RUNNING` 此时可以正常接收客户端请求。  

至此集群版zk启动完毕，选举完毕，数据同步完毕。leader和follower一切准备就绪，开始接收客户端的请求。下面一章节分析客户端请求CRUD对应的服务端的处理流程。 









