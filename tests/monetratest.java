import com.mainstreetsoftworks.MONETRA;

class monetratest {
	private static String host     = "testbox.monetra.com";
	private static int port        = 8665;
	private static int method      = 1; /* 0 = IP, 1 = SSL */
	private static String username = "test_ecomm:public";
	private static String password = "publ1ct3st";

    private static void classapi(Boolean do_report)
	{
		MONETRA conn = new MONETRA("");
		if (method == 1) {
			conn.SetSSL(host, port);
			conn.VerifySSLCert(0);
		} else {
			conn.SetIP(host, port);
		}
		if (conn.Connect() == 0) {
			System.out.println("Connection Failed: " + conn.ConnectionError());
			return;
		}
		System.out.println("Connected, Sending Sale TXN...");

		conn.SetBlocking(1);
		long id;
		
		id = conn.TransNew();
		conn.TransKeyVal(id, "username", username);
		conn.TransKeyVal(id, "password", password);
		conn.TransKeyVal(id, "action", "sale");
		conn.TransKeyVal(id, "account", "4012888888881881");
		conn.TransKeyVal(id, "expdate", "0520");
		conn.TransKeyVal(id, "amount", "12.00");
		conn.TransKeyVal(id, "zip", "32606");
		conn.TransKeyVal(id, "comments", "Multiline Comment \"Test\"\nTest Me");
		if (conn.TransSend(id) == 0) {
			System.out.println("Connection Failed: " + conn.ConnectionError());
			return;
		}
		System.out.println("Response:");
		String[] responsekeys = conn.ResponseKeys(id);
		for (int i = 0; i < responsekeys.length; i++) {
			System.out.println(responsekeys[i] + " : " + conn.ResponseParam(id, responsekeys[i]));
		}
		conn.DeleteTrans(id);

		if (do_report) {
			System.out.println("Sending Unsettled Report Request...");
			id = conn.TransNew();
			conn.TransKeyVal(id, "username", username);
			conn.TransKeyVal(id, "password", password);
			conn.TransKeyVal(id, "action", "admin");
			conn.TransKeyVal(id, "admin", "GUT");
			if (conn.TransSend(id) == 0) {
				System.out.println("Connection Failed: " + conn.ConnectionError());
				return;
			}
			if (conn.ReturnStatus(id) != MONETRA.M_SUCCESS) {
				System.out.println("GUT failed: " + conn.ResponseParam(id, "verbiage"));
			} else {
				System.out.println("Response:");
				conn.ParseCommaDelimited(id);
				int rows = conn.NumRows(id);
				int columns = conn.NumColumns(id);
				System.out.println("Rows: " + rows + " Cols: " + columns);

				for (int i=0; i<columns; i++) {
					if (i != 0)
						System.out.print("|");
					System.out.print(conn.GetHeader(id, i));
				}
				System.out.println("");
				for (int i=0; i<rows; i++) {
					for (int j=0; j<columns; j++) {
						if (j != 0)
							System.out.print("|");
						System.out.print(conn.GetCellByNum(id, j, i));
					}
					System.out.println("");
				}
				System.out.println("");
			}
			conn.DeleteTrans(id);
		}
		System.out.println("Disconnecting...");
		conn.DestroyConn();
		conn = null;
	}


	private static void disconnect_test()
	{
		MONETRA conn = new MONETRA("");
		if (method == 1) {
			conn.SetSSL(host, port);
			conn.VerifySSLCert(0);
		} else {
			conn.SetIP(host, port);
		}
		if (conn.Connect() == 0) {
			System.out.println("Connection Failed: " + conn.ConnectionError());
			return;
		}
		System.out.println("Connected, you have 10s to disconnect from Monetra");
		conn.SetBlocking(1);

		for (int i=10; i >= 0; i--) {
			System.out.print(i + "...");
			conn.uwait(1000000);

			if (conn.Monitor() == 0) {
				System.out.println("");
				System.out.println("Disconnect detected: " + conn.ConnectionError());
				return;
			}
		}
		System.out.println("");

		long id = conn.TransNew();
		conn.TransKeyVal(id, "username", username);
		conn.TransKeyVal(id, "password", password);
		conn.TransKeyVal(id, "action", "chkpwd");
		if (conn.TransSend(id) == 0) {
			System.out.println("Connection Failed: " + conn.ConnectionError());
			return;
		}

		System.out.println(" * Got a response, looks like you didn't disconnect");
		conn.DeleteTrans(id);
		System.out.println("Disconnecting...");
		conn.DestroyConn();
		conn = null;
	}

	private static class RunnerThread extends Thread {
		RunnerThread() {
		}
		public void run() {
			System.out.println("Enter Thread " + Long.toString(Thread.currentThread().getId()));
			classapi(false);
			System.out.println("Exit Thread " + Long.toString(Thread.currentThread().getId()));
		}
	}

	static void thread_test()
	{
		int num_threads = 50;
		RunnerThread[] mythreads = new RunnerThread[num_threads];
		
		System.out.println("Spawning " + num_threads + " threads to connect...");
		
		for (int i=0; i<num_threads; i++) {
			mythreads[i] = new RunnerThread();
			mythreads[i].start();
		}
		
		
		for (int i=0; i<num_threads; i++) {
			try {
				mythreads[i].join();
			} catch (Exception e) {
			}
		}
		System.out.println("All threads exited");
	}


	public static void main(String[] args)
	{
		System.out.println("Version: " + MONETRA.version);

		System.out.println("Using Class API");
		classapi(true);
		
		System.out.println("Thread Test...");
		thread_test();
		
		System.out.println("Performing Disconnect Test...");
		disconnect_test();

    }
}

