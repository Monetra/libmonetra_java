import com.mainstreetsoftworks.MONETRA;
class test_stdapi {

	
	private static String host   = "testbox.monetra.com";
	private static int    port   = 8665;
	private static int    method = 2; /* 1=IP, 2=SSL */
	
	private static String username  = "test_ecomm:public";
	private static String password  = "publ1ct3st";
	private static String trackdata = null;
	private static String account   = "4012888888881881";
	private static String expdate   = "0520";
	private static String amount    = "12.10";
	private static String comments  = "JAVA";
	private static String cardholdername = "Doe, John";
	private static String street    = null;
	private static String zip       = "32606";
	private static String cv        = null;
	private static String stationid = null;;
	private static String clerkid   = null;
	private static String ptrannum  = "4321";
	
	private static MONETRA conn1      = null;
	private static long    identifier = 0;
	
	public static void main(String[] args) {
		System.out.println("Connecting to " + host + ":" + port + "...");

		conn1 = new MONETRA("");
		if (method == 1)
			conn1.SetIP(host, port);
		else if (method == 2)
			conn1.SetSSL(host, port);
		
		conn1.SetTimeout(30);  // Max 30 seconds
		if (conn1.Connect() <= 0) {
			System.out.println("Could not connect to MONETRA: " + conn1.ConnectionError());
			return;
		}
		System.out.println("Connected!");

		System.out.println("Authorizing Transaction...");
		identifier = conn1.TransNew();
		if (identifier == 0) {
			System.out.println("Could not generate new identifier");
			return;
		}
		conn1.TransKeyVal(identifier, "username", username);
		conn1.TransKeyVal(identifier, "password", password);
		conn1.TransKeyVal(identifier, "action", "sale");
		conn1.TransKeyVal(identifier, "account", account);
		conn1.TransKeyVal(identifier, "expdate", expdate);
		conn1.TransKeyVal(identifier, "amount", amount);
		conn1.TransKeyVal(identifier, "street", street);
		conn1.TransKeyVal(identifier, "zip", zip);
		conn1.TransKeyVal(identifier, "cv", cv);
		conn1.TransKeyVal(identifier, "comments", comments);
		conn1.TransKeyVal(identifier, "cardholdername", cardholdername);
		conn1.TransKeyVal(identifier, "stationid", stationid);
		conn1.TransKeyVal(identifier, "clerkid", clerkid);
		conn1.TransKeyVal(identifier, "ptrannum", ptrannum);
		if (conn1.TransSend(identifier) <= 0) {
			System.out.println("TransSend failed");
			return;
		}
		while (conn1.CheckStatus(identifier) != conn1.M_DONE) {
			conn1.Monitor();
			conn1.uwait(20000);
		}
		if (conn1.ReturnStatus(identifier) == conn1.M_SUCCESS) {
			System.out.println("Transaction Authorized");
		} else {
			System.out.println("Transaction Denied");
		}

		String responseparams[] = conn1.ResponseKeys(identifier);
		for(int i=0; i<responseparams.length; i++) {
			System.out.println(responseparams[i] + " = " + conn1.ResponseParam(identifier, responseparams[i]));
		}
		conn1.DeleteTrans(identifier);
		System.out.println("Done");
		conn1.close();
	}
}
