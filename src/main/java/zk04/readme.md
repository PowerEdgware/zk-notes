##zk版本：3.4.14

###zk集群环境事务请求处理流程。  

zk03总结的处理链路：  
### follower处理链路：  
`FollowerRequestProcessor->CommitProcessor->FinalRequestProcessor`  
同步链路 ：  
`SyncRequestProcessor->SendAckRequestProcessor` 

### leader服务处理链路：  
构建leader处理链路：`PrepRequestProcessor->ProposalRequestProcessor->CommitProcessor->ToBeAppliedRequestProcessor->FinalRequestProcessor`

####leader启动完毕，接收客户端请求：  
** 入口 **  
`NIOServerCnxnFactory.run`  
1.每一个客户端连接进来，服务端都会创建一个:`NIOServerCnxn` 处理与客户端之间的连接。  
2.读取客户端请求数据：`NIOServerCnxn.doIO()`  

** 客户端连接请求**   
`NIOServerCnxnFactory.run-->NIOServerCnxn.doIO-->ZooKeeperServer.processConnectRequest`  
1.客户端连接请求的zxid不能大于当前机器节点的：lastProcessedZxid,否则关闭连接     
2.客户端sessionTimeout合理设置 [minSessionTimeout,maxSessionTimeout]   
3.设置客户端sessionId,提交该请求到zkServer的处理链路中。  
`firstProcessor.processRequest(si)` 开始处理连接请求，其中 firstProcessor=`PrepRequestProcessor`   
一直处理到 `FinalRequestProcessor`把处理结果返回。  

**客户端事务请求**  
`NIOServerCnxnFactory.run-->NIOServerCnxn.doIO-->ZooKeeperServer.processPacket(this, incomingBuffer)`  
zkServer提交请求处理，继续走：`firstProcessor.processRequest(si)` 处理流程。  




