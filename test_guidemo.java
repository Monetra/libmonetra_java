import java.awt.*;
import java.awt.event.*;
import java.util.*;
import com.mainstreetsoftworks.MONETRA;

public class test_guidemo
  extends java.applet.Applet
  implements ActionListener {

  private boolean active = false;


  private TextField trackdatafield, cardnumfield, expdatefield, amountfield;
  private TextArea resultarea;

  public static void main(String[] args) {
    Frame fram = new Frame("MONETRA GUI demo");
    java.applet.Applet applet = new test_guidemo();
    fram.add(applet, "Center");
    applet.init();
    fram.setSize(300, 300);
    fram.pack();
    fram.show();
    applet.start();
    applet.stop();
    applet.destroy();
  }

  public void init() {


    /* Lay out all the GUI elements -- tedious but also uninteresting. */

    GridBagLayout lm = new GridBagLayout();
    GridBagConstraints c = new GridBagConstraints();
    Component comp;
    Button but;
    TextField tf;
    TextArea ta;
    setLayout(lm);

    Font fieldfont = new Font("Helvetica", Font.PLAIN, 10);

    /* Create the input textfields */

    c.fill = GridBagConstraints.NONE;
    c.gridx = 0;
    c.gridy = 0;
    c.gridwidth = 1;
    c.weightx = 0;
    comp = new Label("Track Data");
    lm.setConstraints(comp, c);
    add(comp);

    c.fill = GridBagConstraints.HORIZONTAL;
    c.gridx = 1;
    c.gridy = 0;
    c.gridwidth = 4;
    c.weightx = 1.0;
    tf = new TextField("", 0);
    tf.setFont(fieldfont);
    lm.setConstraints(tf, c);
    add(tf);
    trackdatafield = tf;

    c.fill = GridBagConstraints.NONE;
    c.gridx = 0;
    c.gridy = 2;
    c.gridwidth = 1;
    c.weightx = 0;
    comp = new Label("Card No.");
    lm.setConstraints(comp, c);
    add(comp);

    c.fill = GridBagConstraints.HORIZONTAL;
    c.gridx = 1;
    c.gridy = 2;
    c.gridwidth = 4;
    c.weightx = 1.0;
    tf = new TextField("4012888888881881");
    tf.setFont(fieldfont);
    lm.setConstraints(tf, c);
    add(tf);
    cardnumfield = tf;

    c.fill = GridBagConstraints.NONE;
    c.gridx = 0;
    c.gridy = 3;
    c.gridwidth = 1;
    c.weightx = 0;
    comp = new Label("Exp. Date");
    lm.setConstraints(comp, c);
    add(comp);

    c.fill = GridBagConstraints.HORIZONTAL;
    c.gridx = 1;
    c.gridy = 3;
    c.gridwidth = 4;
    c.weightx = 1.0;
    tf = new TextField("0512");
    tf.setFont(fieldfont);
    lm.setConstraints(tf, c);
    add(tf);
    expdatefield = tf;

    c.fill = GridBagConstraints.NONE;
    c.gridx = 0;
    c.gridy = 4;
    c.gridwidth = 1;
    c.weightx = 0;
    comp = new Label("Amount");
    lm.setConstraints(comp, c);
    add(comp);

    c.fill = GridBagConstraints.HORIZONTAL;
    c.gridx = 1;
    c.gridy = 4;
    c.gridwidth = 4;
    c.weightx = 1.0;
    tf = new TextField("1.00");
    tf.setFont(fieldfont);
    lm.setConstraints(tf, c);
    add(tf);
    amountfield = tf;

    /* Create the action buttons */

    c.fill = GridBagConstraints.NONE;
    c.gridx = 0;
    c.gridy = 5;
    c.gridwidth = 1;
    c.weightx = 0;
    comp = new Label("Perform");
    lm.setConstraints(comp, c);
    add(comp);

    c.fill = GridBagConstraints.HORIZONTAL;
    c.gridx = 1;
    c.gridy = 5;
    c.gridwidth = 1;
    c.weightx = 1.0;
    but = new Button("OK");
    lm.setConstraints(but, c);
    add(but);
    but.setActionCommand("OK");
    but.addActionListener(this);

    c.fill = GridBagConstraints.HORIZONTAL;
    c.gridx = 2;
    c.gridy = 5;
    c.gridwidth = 1;
    c.weightx = 1.0;
    but = new Button("EXIT");
    lm.setConstraints(but, c);
    add(but);
    but.setActionCommand("EXIT");
    but.addActionListener(this);

    /* Create the result area. */

    c.fill = GridBagConstraints.BOTH;
    c.gridx = 0;
    c.gridy = 6;
    c.gridwidth = 5;
    c.weighty = 1.0;
    ta = new TextArea();
    ta.setFont(fieldfont);
    ta.setEditable(false);
    lm.setConstraints(ta, c);
    add(ta);
    resultarea = ta;
  }

  public void actionPerformed(ActionEvent ev) {
    String cmd = ev.getActionCommand();
    String text;
    String authnum;
    MONETRA mcve1 = null;
    if (cmd.equals("OK")) {
      String trackdata=trackdatafield.getText();
      String amount=amountfield.getText();
      String ccnum=cardnumfield.getText();
      String expdate=expdatefield.getText();
      String Result="\nTRACKDATA: " + trackdata + "\nCCNUM: " + ccnum + "\nExp: " + expdate + "\nAmount: " + amount + "\n";
      showResult(Result);

      mcve1 = new MONETRA("");
      mcve1.SetSSL("testbox.monetra.com", 8665);
      mcve1.SetTimeout(30);

      showResult("Connecting to MONETRA");
      if (mcve1.Connect() <= 0) {
        showResult("Connection failed");
	return;
      }
      long identifier=mcve1.TransNew();
      mcve1.TransParam(identifier, mcve1.MC_TRANTYPE, mcve1.MC_TRAN_SALE);
      mcve1.TransParam(identifier, mcve1.MC_USERNAME, "test_ecomm:public");
      mcve1.TransParam(identifier, mcve1.MC_PASSWORD, "publ1ct3st");

      mcve1.TransParam(identifier, mcve1.MC_ACCOUNT, ccnum);
      mcve1.TransParam(identifier, mcve1.MC_EXPDATE, expdate);
      if (trackdata.length() > 0)
	      mcve1.TransParam(identifier, mcve1.MC_TRACKDATA, trackdata);
      mcve1.TransParam(identifier, mcve1.MC_AMOUNT, amount);
      mcve1.TransSend(identifier);
      while (mcve1.CheckStatus(identifier) != mcve1.MCVE_DONE) {
        mcve1.Monitor();
      }

      if (mcve1.ReturnStatus(identifier) == mcve1.MCVE_SUCCESS) {
        showResult("Transaction Authorized");
      } else {
        showResult("Transaction Denied");
      }

      text=mcve1.TEXT_Code(mcve1.ReturnCode(identifier));
      showResult("Code: " + text);

      text=mcve1.TEXT_AVS(mcve1.TransactionAVS(identifier));
      showResult("AVS: " + text);

      text=mcve1.TEXT_CV(mcve1.TransactionCV(identifier));
      showResult("CV: " + text);

      text=mcve1.TransactionText(identifier);
      showResult("Text: " + text);

      text=mcve1.ResponseParam(identifier, "raw_code");
      showResult("Raw_Code: " + text);

      text=mcve1.ResponseParam(identifier, "raw_avs");
      showResult("Raw_Avs: " + text);

      text=mcve1.ResponseParam(identifier, "raw_cv");
      showResult("Raw_Cv: " + text);

      authnum=mcve1.TransactionAuth(identifier);

      if (mcve1.ReturnStatus(identifier) == mcve1.MCVE_SUCCESS) {
        showResult("AuthNum: " + authnum);
      }

      mcve1.DeleteResponse(identifier);
      mcve1.close();
    } else {
      System.exit(0);
    }
  }

  private void showResult(String resstr) {
    String str = resultarea.getText();
    if (!str.equals("")) {
      str = str + "\n";
    }
    str = str + resstr;
    resultarea.setText(str);
  }


}
