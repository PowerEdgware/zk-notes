package com.zk01;

import java.io.IOException;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;

public class ZookeeperDemo implements Watcher {

	static String connectString = "192.168.88.135:2182,192.168.88.135:2181,192.168.88.135:2183";
	static int sessionTimeout = 300 * 1000;
	static ZooKeeper zk = null;
 {
		try {
			zk = new ZooKeeper(connectString, sessionTimeout, this);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public static void main(String[] args) {
		try {
			new ZookeeperDemo();
			// CRUD
			String path = "/test";
			Stat stat = zk.exists(path, true);
			if (stat == null) {
				String result = zk.create(path, "demo".getBytes("UTF-8"), ZooDefs.Ids.OPEN_ACL_UNSAFE,
						CreateMode.PERSISTENT);
				System.out.println(result);
			}
			
			System.out.println(stat);
		} catch (IOException e) {
			e.printStackTrace();
		} catch (KeeperException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		try {
			System.in.read();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@Override
	public void process(WatchedEvent event) {
		System.out.println("Event Recv:" + event);
		if(event.getType().equals(EventType.NodeDataChanged)) {
			try {
				//循环监听path
				zk.exists(event.getPath(), true);
			} catch (KeeperException e) {
				e.printStackTrace();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}
}
