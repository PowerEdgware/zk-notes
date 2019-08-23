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

** 启动类 QuorumPeerMain**  
 1.启动   
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


2.leader选举  
在QuorumPeer的run方法中，一开始集群中每个QuorumPeer节点的都处于寻找leader节点状态。  随后调用：`FastLeaderElection.lookForLeader` 进行选举，  
此过程一直阻塞，直到选出 leader 
* `FastLeaderElection.lookForLeader`选举算法  
第一步：是发送自己的选票给集群所有节点(整个选举过程就是投票，开始的一票投给自己)    
第二步：接受选票，比较选票，大的留下更新当前候选ProposalLeader信息  
第三步：判断收到的票据是否有超出过半的票是候选leader，是则设置当前节点状态，结束选举，否则继续。

* 发送接收消息流程  
FastLeaderElection 内部维护了两个线程：
`WorkerReceiver` 











