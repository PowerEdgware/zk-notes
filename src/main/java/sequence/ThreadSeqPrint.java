package sequence;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

public class ThreadSeqPrint {

	public static void main(String[] args) throws InterruptedException {

		SeqThread t1=new SeqThread("t1");
		SeqThread t2=new SeqThread("t2");
		SeqThread t3=new SeqThread("t3");
		
		t1.SetLock(t2);
		t2.SetLock(t3);
		t3.SetLock(t1);
		
		t1.start();
//		LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
//		t1.join(10);
		t2.start();
//		t2.join(10);
//		LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
		
		t3.start();
	}
}

class SeqThread extends Thread {
	static AtomicInteger gloableNum = new AtomicInteger(0);
	private Object lock;

	public SeqThread(String name)
	{
		super(name);
	}
	public void SetLock(Object lock) {
		this.lock=lock;
	}

	@Override
	public void run() {
		while (gloableNum.get() < 99) {
			synchronized (this) {
				synchronized (lock) {
					gloableNum.getAndIncrement();
					System.out.println(getName() + " seq:" + gloableNum.get());
					lock.notify();
				}
				if(gloableNum.get()>99) {
					break;
				}
				try {
					wait();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
	}
}
