import com.mainstreetsoftworks.MONETRA;

class test_oldapi {
  private static MONETRA mcve1 = null;
  private static long identifier=-1;

  private static String text="";
  private static String authnum="";

  public static void main(String[] args) {
         System.out.println("Authorizing Transaction...");
	 
	 /* CAfile parameter to  new MONETRA  only needs to be set for SSL */
	 mcve1 = new MONETRA("");

	 /* Set IP address and Port Number to connect to */
         mcve1.SetSSL("testbox.monetra.com", 8665);
	 
	 /* Set Maximum amount of time a transaction can stay in the MCVE queue in seconds */
	 mcve1.SetTimeout(30);
         
         /* Set Connection type to blocking so that we don't have to loop waiting for a response */
         mcve1.SetBlocking(1);
 
	 /* Connect to the MCVE server */
         if (mcve1.Connect() <= 0) {
	   System.out.println("Could not connect to MONETRA!");
	   return;
	 }

	 /* New syntax below */

           /* Create place in Queue */
	   identifier=mcve1.TransNew();

	   /* Specify transaction Type */
	   mcve1.TransParam(identifier, mcve1.MC_TRANTYPE, mcve1.MC_TRAN_ADMIN);
	   mcve1.TransParam(identifier, mcve1.MC_ADMIN, mcve1.MC_ADMIN_BT);

           /* Set Username and Password */
	   mcve1.TransParam(identifier, mcve1.MC_USERNAME, "test_ecomm:public");
	   mcve1.TransParam(identifier, mcve1.MC_PASSWORD, "publ1ct3st");

	   /* Set Acct, ExpDate, and Amount */
	   //mcve1.TransParam(identifier, mcve1.MC_ACCOUNT, "4012888888881");
	   //mcve1.TransParam(identifier, mcve1.MC_EXPDATE, "0512");
	   //mcve1.TransParam(identifier, mcve1.MC_AMOUNT, "14.00");

	   /* Set optional fields such as comments, and ptrannum */
	   //mcve1.TransParam(identifier, mcve1.MC_COMMENTS, "NEW JAVA");
	   //mcve1.TransParam(identifier, mcve1.MC_PTRANNUM, "123456");

	   /* Go ahead and send the transaction! */
	   mcve1.TransSend(identifier);

         /*
	   NOTE: Above is the same as performing the below statement

	   identifier=mcve1.Sale("vitale", "test", "", "4012888888881", "0512", 14.00, "", "", "", "JAVA", "", "", 123456);

	 */

	 /* Loop Until Transaction is Complete */

         /*
          *  Not necessary because we set the connection into blocking mode
          *while (mcve1.CheckStatus(identifier) != mcve1.MCVE_DONE) {
          *   mcve1.Monitor();
          *}
          */


         if (mcve1.ReturnStatus(identifier) == mcve1.MCVE_SUCCESS) {
           System.out.println("Transaction Authorized");
         } else {
           System.out.println("Transaction Denied");
         }

	 text=mcve1.GetCommaDelimited(identifier);
	 System.out.println("comma: " + text);
	 /*
	 text=mcve1.TEXT_Code(mcve1.ReturnCode(identifier));
         System.out.println("Code: " + text);

         text=mcve1.TEXT_AVS(mcve1.TransactionAVS(identifier));
         System.out.println("AVS: " + text);

	 text=mcve1.TEXT_CV(mcve1.TransactionCV(identifier));
	 System.out.println("CV: " + text);

	 text=mcve1.TransactionText(identifier);
         System.out.println("Text: " + text);

         text=mcve1.ResponseParam(identifier, "raw_code");
         System.out.println("Raw_Code: " + text);

         text=mcve1.ResponseParam(identifier, "raw_avs");
         System.out.println("Raw_Avs: " + text);

         text=mcve1.ResponseParam(identifier, "raw_cv");
         System.out.println("Raw_Cv: " + text);

	 authnum=mcve1.TransactionAuth(identifier);

 	 if (mcve1.ReturnStatus(identifier) == mcve1.MCVE_SUCCESS) {
           System.out.println("AuthNum: " + authnum);
         }
         */

	 /* If I knew how to print an int, I'd do it ... */
	 /* this is the Transaction Tracking ID (TTID) */
	 /*
	 mcve1.TransactionID(identifier);

         */

	 /* Clean Up Memory for Transaction */
	 mcve1.DeleteResponse(identifier);
	 mcve1.close();
	 System.out.println("Done");

    }
}
