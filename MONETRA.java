/*
 * Copyright 2015 Main Street Softworks, Inc. All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 * 
 *  1. Redistributions of source code must retain the above copyright notice, this list of
 *     conditions and the following disclaimer.
 *
 *  2. Redistributions in binary form must reproduce the above copyright notice, this list
 *     of conditions and the following disclaimer in the documentation and/or other materials
 *     provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY MAIN STREET SOFTWORKS INC ``AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL MAIN STREET SOFTWORKS INC OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 * 
 * The views and conclusions contained in the software and documentation are those of the
 * authors and should not be interpreted as representing official policies, either expressed
 * or implied, of Main Street Softworks, Inc.
 */

/* TODO:
 *   - Properly implement non-blocking SSL at some point.  We should be using NIO and
 *     SSLEngine because InputStreamReader's .ready() method doesn't work for SSL, but
 *     instead, we use .read() in a blocking mode, but just throw it in its own thread.
 *   - Support SSL Client certificates?
 *   - Support some form of keystore selection for verifying the server certificate?
 */

package com.mainstreetsoftworks;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class MONETRA {
	public static final String version = "0.9.6";

	/* Base implementation, emulate our libmonetra API as closely as possible */

	private static final int M_CONN_SSL = 1;
	private static final int M_CONN_IP  = 2;

	private static final int M_TRAN_STATUS_NEW  = 1;
	private static final int M_TRAN_STATUS_SENT = 2;
	private static final int M_TRAN_STATUS_DONE = 3;

	public static final int M_ERROR   = -1;
	public static final int M_FAIL    =  0;
	public static final int M_SUCCESS =  1;

	public static final int M_DONE    =  2;
	public static final int M_PENDING =  3;

	public class M_TRAN {
		public long                      id              = 0;
		public int                       status          = M_TRAN_STATUS_NEW;
		public Boolean                   comma_delimited = false;
		public Hashtable<String,String>  in_params       = new Hashtable<String,String>();
		public Hashtable<String,String>  out_params      = new Hashtable<String,String>();
		public byte[]                    raw_response    = null;
		public String[][]                csv             = null;
	}

	private class M_CONN {
		public Boolean                blocking     = false;
		public String                 conn_error   = "";
		public int                    conn_timeout = 10;
		public String                 host         = null;
		public long                   last_id      = 0;
		public int                    method       = M_CONN_IP;
		public int                    port         = 8333;
		public byte[]                 readbuf      = null;
		public int                    last_parse_len = 0;
		public String                 ssl_cafile   = null;
		public Boolean                ssl_verify   = false;
		public String                 ssl_cert     = null;
		public String                 ssl_key      = null;
		public int                    timeout      = 0;
		public Hashtable<Long,M_TRAN> tran_array   = new Hashtable<Long,M_TRAN>();
		public Boolean                verify_conn  = true;
		public byte[]                 writebuf     = null;
		public Socket                 fd           = null;
		public OutputStream           outstream    = null;
		public InputStream            instream     = null;
		
		public ReaderThread           rThread      = null;
		public Lock                   connlock     = new ReentrantLock();
		public Condition              readwait     = connlock.newCondition();
	}

	private M_CONN conn;

	public MONETRA(String cafile) {
		conn = new M_CONN();
		conn.ssl_cafile = cafile;
	}
	
	public void finalize() {
		close();
	}
	
	public void close()
	{
		DestroyConn();
	}

	public int SetIP(String host, int port)
	{
		conn.host   = host;
		conn.port   = port;
		conn.method = M_CONN_IP;
		
		return 1;
	}
	
	public int SetSSL(String host, int port)
	{
		conn.host   = host;
		conn.port   = port;
		conn.method = M_CONN_SSL;
		
		return 1;
	}
	
	public int SetDropFile(String directory)
	{
		/* NOT SUPPORTED */
		return 0;
	}

	public int SetBlocking(int tf)
	{
		conn.blocking = (tf == 0)?false:true;

		return 1;
	}
	
	public synchronized long TransNew()
	{
		M_TRAN tran = new M_TRAN();
		tran.id     = ++conn.last_id;
		
		conn.tran_array.put(tran.id, tran);

		return tran.id;
	}

	private static long time()
	{
		long out = (new java.util.Date()).getTime() / 1000;
		return out;
	}
	
	private Boolean verifyping()
	{
		Boolean    blocking      = conn.blocking;
		long       id;
		final long max_ping_time = 5;
		
		SetBlocking(0);

		id = TransNew();
		TransKeyVal(id, "action", "ping");
		
		if (TransSend(id) == 0) {
			DeleteTrans(id);
			return false;
		}
		
		long lasttime = time();
		while (CheckStatus(id) == M_PENDING && time() - lasttime <= max_ping_time) {
			/* Calculate our Monitor timeout in milliseconds */
			long timeout_ms = (max_ping_time - (time() - lasttime)) * 1000;

			/* Sanity checks ... neither of these should really be possible */
			if (timeout_ms < 0)
				timeout_ms = 0;
			else if (timeout_ms > max_ping_time * 1000)
				timeout_ms = max_ping_time * 1000;

			/* Wait for data to be read and possibly parsed */
			if (Monitor(timeout_ms) == 0) {
				break;
			}
		}
		
		SetBlocking(blocking?1:0);

		int status = CheckStatus(id);
		DeleteTrans(id);
		if (status != M_DONE) {
			return false;
		}

		return true;
	}


	public String ConnectionError()
	{
		return conn.conn_error;
	}
	

	public int MaxConnTimeout(int secs)
	{
		conn.conn_timeout = secs;
		return 1;
	}
	
	
	public int ValidateIdentifier(int tf)
	{
		/* Always validated, stub for compatibility */
		return 1;
	}
	
	
	public int VerifyConnection(int tf)
	{
		conn.verify_conn = (tf == 0)?false:true;
		return 1;
	}
	
	
	public int VerifySSLCert(int tf)
	{
		conn.ssl_verify = (tf == 0)?false:true;
		return 1;
	}
	
	
	public int SetSSL_CAfile(String cafile)
	{
		conn.ssl_cafile = cafile;
		return 1;
	}
	

	public int SetSSL_Files(String sslkeyfile, String sslcertfile)
	{
		if (sslkeyfile == null || sslkeyfile.length() == 0 || sslcertfile == null || sslcertfile.length() == 0)
			return 0;
		
		conn.ssl_cert = sslcertfile;
		conn.ssl_key  = sslkeyfile;
		
		return 1;
	}
	

	public int SetTimeout(int secs)
	{
		conn.timeout = secs;
		return 1;
	}


	private M_TRAN findtranbyid(long id)
	{
		return conn.tran_array.get(id);
	}


	public int TransKeyVal(long id, String key, String val)
	{
		M_TRAN tran = findtranbyid(id);

		/* Invalid ptr, or transaction has already been sent out */
		if (tran == null || tran.status != M_TRAN_STATUS_NEW)
			return 0;
	
		if (val == null)
			return 0;
		
		tran.in_params.put(key, val);
	
		return 1;
	}
	
	private String base64Encode(byte[] data)
	{
		return Base64.encodeToString(data, true);
	}
	
	private byte[] base64Decode(String data)
	{
		return Base64.decode(data);
	}
	
	public int TransBinaryKeyVal(long id, String key, byte[] val)
	{
		return TransKeyVal(id, key, base64Encode(val));
	}
	
	public int CheckStatus(long id)
	{
		M_TRAN tran = findtranbyid(id);
		if (tran == null ||
			(tran.status != M_TRAN_STATUS_SENT && tran.status != M_TRAN_STATUS_DONE))
			return M_ERROR;

		if (tran.status == M_TRAN_STATUS_SENT)
			return M_PENDING;

		return M_DONE;
	}
	
	
	public int DeleteTrans(long id)
	{
		M_TRAN tran = findtranbyid(id);
		if (tran == null)
			return 0;
		
		conn.tran_array.remove(id);

		return 1;
	}

	
	public long[] CompleteAuthorizations()
	{
		int count = 0;

		for (long id : conn.tran_array.keySet()) {
			if (conn.tran_array.get(id).status == M_TRAN_STATUS_DONE)
				count++;
		}
		long[] id_array = new long[count];
		count = 0;
		for (long id : conn.tran_array.keySet()) {
			if (conn.tran_array.get(id).status == M_TRAN_STATUS_DONE)
				id_array[count++] = id;
		}
		return id_array;
	}
	
	
	public int TransInQueue()
	{
		return conn.tran_array.size();
	}
	

	public int TransactionsSent()
	{
		if (conn.writebuf.length != 0)
			return 0;
		return 1;
	}
	
	
	public static void uwait(int usec)
	{
		try {
			Thread.sleep(usec / 1000);
		} catch (InterruptedException e) {
		}
	}

	public String GetCell(long id, String col, int row)
	{
		M_TRAN tran = findtranbyid(id);
		if (tran == null)
			return null;

		/* Invalid ptr, or transaction has not returned */
		if (tran.status != M_TRAN_STATUS_DONE)
			return null;
		if (!tran.comma_delimited)
			return null;
		if (row+1 >= tran.csv.length)
			return null;
			
		for (int i=0; i<tran.csv[0].length; i++) {
			if (col.equalsIgnoreCase(tran.csv[0][i]))
				return tran.csv[row+1][i];
		}
		return null;
	}
	

	public byte[] GetBinaryCell(long id, String col, int row)
	{
		byte[] ret = null;
		
		String cell = GetCell(id, col, row);
		if (cell != null)
			ret = base64Decode(cell);
		return ret;
	}

	public String GetCellByNum(long id, int col, int row)
	{
		M_TRAN tran = findtranbyid(id);
		if (tran == null)
			return null;

		/* Invalid ptr, or transaction has not returned */
		if (tran.status != M_TRAN_STATUS_DONE)
			return null;
		if (!tran.comma_delimited)
			return null;
		if (row+1 >= tran.csv.length || col >= tran.csv[0].length)
			return null;
			
		return tran.csv[row+1][col];
	}
	

	public String GetCommaDelimited(long id)
	{
		M_TRAN tran = findtranbyid(id);
		if (tran == null)
			return null;

		/* Invalid ptr, or transaction has not returned */
		if (tran.status != M_TRAN_STATUS_DONE)
			return null;
		
		try {
			return new String(tran.raw_response, "UTF8");
		} catch (java.io.UnsupportedEncodingException e) {
		}
		return null;
	}
	

	public String GetHeader(long id, int col)
	{
		M_TRAN tran = findtranbyid(id);
		if (tran == null)
			return null;

		/* Invalid ptr, or transaction has not returned */
		if (tran.status != M_TRAN_STATUS_DONE)
			return null;
		if (!tran.comma_delimited)
			return null;
		if (col >= tran.csv[0].length)
			return null;

		return tran.csv[0][col];
	}


	public int IsCommaDelimited(long id)
	{
		M_TRAN tran = findtranbyid(id);
		if (tran == null)
			return 0;

		/* Invalid ptr, or transaction has not returned */
		if (tran.status != M_TRAN_STATUS_DONE)
			return 0;
		return tran.comma_delimited?1:0;
	}


	public int NumColumns(long id)
	{
		M_TRAN tran = findtranbyid(id);
		if (tran == null)
			return 0;

		/* Invalid ptr, or transaction has not returned */
		if (tran.status != M_TRAN_STATUS_DONE)
			return 0;
		if (!tran.comma_delimited)
			return 0;
		return tran.csv[0].length;
	}


	public int NumRows(long id)
	{
		M_TRAN tran = findtranbyid(id);
		if (tran == null)
			return 0;

		/* Invalid ptr, or transaction has not returned */
		if (tran.status != M_TRAN_STATUS_DONE)
			return 0;
		if (!tran.comma_delimited)
			return 0;
		return tran.csv.length-1;
	}

	public String[] ResponseKeys(long id)
	{
		M_TRAN tran = findtranbyid(id);
		if (tran == null)
			return null;

		/* Invalid ptr, or transaction has not returned */
		if (tran.status != M_TRAN_STATUS_DONE)
			return null;

		String[] ret = new String[tran.out_params.size()];
		
		Enumeration<String> en = tran.out_params.keys();
		int count = 0;
		while (en.hasMoreElements()) {
			ret[count++] = en.nextElement();
		}
		
		return ret;
	}

	public String ResponseParam(long id, String key)
	{
		M_TRAN tran = findtranbyid(id);
		if (tran == null)
			return null;

		/* Invalid ptr, or transaction has not returned */
		if (tran.status != M_TRAN_STATUS_DONE)
			return null;

		return tran.out_params.get(key);
	}


	public int ReturnStatus(long id)
	{
		M_TRAN tran = findtranbyid(id);
		if (tran == null)
			return M_ERROR;

		/* Invalid ptr, or transaction has not returned */
		if (tran.status != M_TRAN_STATUS_DONE)
			return M_ERROR;

		if (tran.comma_delimited)
			return M_SUCCESS;

		String code = ResponseParam(id, "code");
		if (code == null)
			return M_FAIL;
		
		if (code.equalsIgnoreCase("AUTH") || code.equalsIgnoreCase("SUCCESS"))
			return M_SUCCESS;

		return M_FAIL;
	}

	private Boolean ip_connect()
	{
		Socket socket = new Socket();
		
		try {
			socket.bind(null);
			socket.connect(new InetSocketAddress(conn.host, conn.port), conn.conn_timeout * 1000);

			/* Disable Nagle algorithm, should reduce latency */
			socket.setTcpNoDelay(true);
		} catch (java.net.SocketTimeoutException te) {
			conn.conn_error = "Connection Timeout";
			return false;
		} catch (Exception e) {
			conn.conn_error = "Connection Failed: " + e.getMessage();
			return false;
		}

		conn.fd = socket;
		return true;
	}
	
	private Boolean ssl_connect()
	{
		if (!ip_connect())
			return false;

		Socket sslfd;
		
		try {
			SSLSocketFactory factory;
			if (conn.ssl_verify) {
				factory = (SSLSocketFactory)SSLSocketFactory.getDefault();
			} else {
				TrustManager[] trustAllCerts = new TrustManager[] {
					new X509TrustManager() {
						public java.security.cert.X509Certificate[] getAcceptedIssuers()
						{
							return null;
						}
						public void checkClientTrusted(	java.security.cert.X509Certificate[] certs, String authType)
						{
							// Trust always
						}
						public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType)
						{
							// Trust always
						}
					}
				};
				SSLContext sc = SSLContext.getInstance("TLS");
				sc.init(null, trustAllCerts, new java.security.SecureRandom());
				factory = sc.getSocketFactory();
			}
		
			sslfd = factory.createSocket(conn.fd, conn.host, conn.port, true);
		} catch (Exception e) {
			conn.conn_error = "SSL Negotiation failed: " + e.getMessage();
			try {
				conn.fd.close();
			} catch (Exception e2) {
			}
			conn.fd = null;
			return false;
		}
		
		conn.fd = sslfd;

		return true;
	}
	
	private static class ReaderThread extends Thread {
		M_CONN conn;
		ReaderThread(M_CONN conn) {
			this.conn = conn;
		}
		public void run() {
			byte[] buf = new byte[8192];

			while(true) {
				int bytes_read = 0;
				try {
//System.out.println("Waiting on data...");
					bytes_read = conn.instream.read(buf, 0, buf.length);
//System.out.println("Got data");
					if (bytes_read <= 0) {
//System.out.println("Got failure");
						if (conn.rThread != null) {
							conn.rThread = null;
							conn.conn_error = "Read Failed: Remote Disconnect";
							try {
								conn.fd.close();
							} catch(Exception te) {
							}
							conn.instream = null;
							conn.outstream = null;
							conn.fd = null;
						}
						/* Wake up any waiters */
						conn.connlock.lock();
						conn.readwait.signalAll();
						conn.connlock.unlock();
						return;
					}

					/* Append Data */
					conn.connlock.lock();
					conn.readbuf = byteArrayConcat(conn.readbuf, buf, bytes_read);
					/* Wake up any waiters */
					conn.readwait.signalAll();
					conn.connlock.unlock();
//System.out.println("Read " + bytes_read + " bytes");					
//System.out.println("READ: " + byteArrayToString(conn.readbuf));
				} catch (Exception e) {
					if (conn.rThread != null) {
						conn.rThread = null;
						conn.conn_error = "Read Failed: " + e.getMessage();
						try {
							conn.fd.close();
						} catch(Exception te) {
						}
						conn.instream = null;
						conn.outstream = null;
						conn.fd = null;
					}

					/* Wake up any waiters */
					conn.connlock.lock();
					conn.readwait.signalAll();
					conn.connlock.unlock();
					return;
				}
			}
		}
	}

	public int Connect()
	{
		if (conn.method == M_CONN_IP) {
			if (!ip_connect())
				return 0;
		} else if (conn.method == M_CONN_SSL) {
			if (!ssl_connect())
				return 0;
		} else {
			conn.conn_error = "Unknown connectivity method";
			return 0;
		}
		
		try {
			conn.outstream = conn.fd.getOutputStream();
			conn.instream  = conn.fd.getInputStream();
		} catch (Exception e) {
			conn.conn_error = "Failed to initialize stream: " + e.getMessage();
			closeConn();
			return 0;
		}

		conn.rThread = new ReaderThread(conn);
		conn.rThread.start();
		
		if (conn.verify_conn && !verifyping()) {
			String err      = conn.conn_error;
			conn.conn_error = "PING request failed: " + err;
			closeConn();
			return 0;
		}

		return 1;
	}
	
	private void closeConn()
	{
		try {
			if (conn.rThread != null)
				conn.rThread.interrupt();
			conn.rThread = null;
			conn.fd.close();
			conn.instream = null;
			conn.outstream = null;
		} catch (Exception e) {
		}
		conn.fd = null;
	}
	
	public void DestroyConn()
	{
		closeConn();
		conn = null;
	}
	
	private static byte[] byteArrayConcat(byte[] str1, byte[] str2, int str2_len)
	{
		int str1_len;
		
		if (str1 == null)
			str1_len = 0;
		else
			str1_len = str1.length;

		if (str2_len == -1) {
			if (str2 == null)
				str2_len = 0;
			else
				str2_len = str2.length;
		}

		if (str1_len + str2_len == 0)
			return null;
			
		byte[] ret = new byte[str1_len + str2_len];
		if (str1_len > 0)
			System.arraycopy(str1, 0, ret, 0, str1_len);
		if (str2_len > 0)
			System.arraycopy(str2, 0, ret, str1_len, str2_len);
		return ret;
	}
	
	private static byte[] byteArraySubStr(byte[] str, int start, int length)
	{
		if (str == null || str.length < length)
			return null;

		byte[] ret = new byte[length];
		System.arraycopy(str, start, ret, 0, length);
		return ret;
	}
	
	private static byte[] stringToByteArray(String buf)
	{
		try {
			return buf.getBytes("UTF8");
		} catch (Exception e) {
		}
		return null;
	}
	
	private static String byteArrayToString(byte[] data)
	{
		try {
			return new String(data, "UTF8");
		} catch (Exception e) {
		}
		return null;
	}
	
	private static int byteArrayChr(byte[] data, byte chr)
	{
		for (int i=0; i<data.length; i++) {
			if (data[i] == chr)
				return i;
		}
		return -1;
	}

	private static byte[][] byteArrayExplode(byte delim, byte[] data, byte quote_char, int max_sects)
	{
		Boolean on_quote;
		int beginsect;
		int num_sects;

		if (data == null || data.length == 0)
			return null;

		/* We need to first count how many lines we have */
		num_sects = 1;
		on_quote  = false;
		for (int i=0; i<data.length && (max_sects == 0 || num_sects < max_sects); i++) {
			if (quote_char != 0 && data[i] == quote_char) {
				/* Doubling the quote char acts as escaping */
				if (on_quote && data.length - i > 1 && data[i+1] == quote_char) {
					i++;
					continue;
				} else if (on_quote) {
					on_quote = false;
				} else {
					on_quote = true;
				}
			}
			if (data[i] == delim && !on_quote) {
				num_sects++;
			}
		}

		byte[][] ret = new byte[num_sects][];
		beginsect     = 0;
		int cnt       = 1;
		on_quote      = false;

		for (int i=0; i<data.length && cnt < num_sects; i++) {
			if (quote_char != 0 && data[i] == quote_char) {
				/* Doubling the quote char acts as escaping */
				if (on_quote && data.length - i > 1 && data[i+1] == quote_char) {
					i++;
					continue;
				} else if (on_quote) {
					on_quote = false;
				} else {
					on_quote = true;
				}
			}
			if (data[i] == delim && !on_quote) {
				ret[cnt-1] = byteArraySubStr(data, beginsect, i - beginsect);
				beginsect  = i + 1;
				cnt++;
			}
		}
		/* Capture last segment */
		ret[cnt-1] = byteArraySubStr(data, beginsect, data.length - beginsect);

		return ret;
	}
	
	private static byte[] byteArrayTrim(byte[] str)
	{
		int start = -1;
		int stop  = -1;
		int length = 0;
		int i;
		
		if (str == null || str.length == 0)
			return new byte[0];

		for (i=0; i<str.length; i++) {
			if (str[i] == (byte)'\r' || str[i] == (byte)'\n' ||
				str[i] == (byte)'\t' || str[i] == (byte)' ')
				continue;
			start = i;
			break;
		}
		
		if (start == -1)
			return new byte[0];
		
		for (i=str.length-1; i>= 0; i--) {
			if (str[i] == (byte)'\r' || str[i] == (byte)'\n' ||
				str[i] == (byte)'\t' || str[i] == (byte)' ')
				continue;
			stop = i;
			break;
		}
		
		if (stop == -1)
			return new byte[0];
		
		length = (stop-start)+1;
		if (length == 0)
			return new byte[0];
		if (length == str.length)
			return str;
		
		return byteArraySubStr(str, start, length);
	}

	private static String M_remove_dupe_quotes(byte[] str)
	{
		int i = 0;
		int len = str.length;

		/* No quotes */
		if (byteArrayChr(str, (byte)'"') == -1)
			return byteArrayToString(str);

		/* Surrounding quotes, remove */
		if (str[0] == (byte)'"' && str[len-1] == (byte)'"') {
			i   += 1;
			len -= 1;
		}

		ByteArrayOutputStream out_str = new ByteArrayOutputStream();
		for ( ; i<len; i++) {
			if (str[i] == (byte)'"' && i < len-1 && str[i+1] == (byte)'"') {
				out_str.write((byte)'"');
				i++;
			} else if (str[i] != (byte)'"') {
				out_str.write(str[i]);
			}
		}
		return byteArrayToString(out_str.toByteArray());
	}
	
	private static Boolean M_verify_comma_delimited(byte[] data)
	{
		for (int i=0; i<data.length; i++) {
			/* If hit a new line or a comma before an equal sign, must
			 * be comma delimited */
			if (data[i] == '\r' ||
				data[i] == '\n' ||
				data[i] == ',')
				return true;

			/* If hit an equal sign before a new line or a comma, must be
			 * key/val */
			if (data[i] == '=')
				return false;
		}
		/* Who knows?  Should never get here */
		return true;
	}
	
	public synchronized int TransSend(long id)
	{
		M_TRAN tran = findtranbyid(id);

		/* Invalid ptr, or transaction has already been sent out */
		if (tran == null || tran.status != M_TRAN_STATUS_NEW)
			return 0;

		tran.status = M_TRAN_STATUS_SENT;

		/* Structure Transaction */
		ByteArrayOutputStream tran_str = new ByteArrayOutputStream();

		/* STX */
		tran_str.write(0x02);
		
		/* ID */
		byte[] idstr = stringToByteArray(Long.toString(tran.id));
		tran_str.write(idstr, 0, idstr.length);
		idstr = null;
		
		/* FS */
		tran_str.write(0x1c);
		
		/* PING is specially formed */
		String action = tran.in_params.get("action");
		String message = "";
		if (action != null && action.equalsIgnoreCase("ping")) {
			message += "PING";
		} else {
			/* Each key/value pair in array as key="value" */
			Enumeration<String> en = tran.in_params.keys();
			while (en.hasMoreElements()) {
				String key = en.nextElement();
				String val = tran.in_params.get(key);
				message += key + "=\"" + val.replaceAll("\"", "\"\"") + "\"\r\n";
			}

			/* Add timeout if necessary */
			if (conn.timeout != 0) {
				message += "timeout=" + conn.timeout + "\r\n";
			}
		}
		byte[] message_bytes = stringToByteArray(message);
		tran_str.write(message_bytes, 0, message_bytes.length);
		message_bytes = null;
		message = null;

		/* ETX */
		tran_str.write(0x03);

		conn.writebuf = byteArrayConcat(conn.writebuf, tran_str.toByteArray(), -1);
		tran_str = null;
		
		if (conn.blocking) {
			while (CheckStatus(id) == M_PENDING) {
				/* Wait indefinitely until we have read some data */
				if (Monitor(-1) == 0)
					return 0;
			}
		}

		return 1;
	}

	/* Return true if there is some data available, even if the data is
	 * a connection has been closed, otherwise return false meaning there
	 * is no need to attempt a parse */
	private Boolean Monitor_read(long wait_ms)
	{
		Boolean retval;

		/* ReaderThread does the actual reading */
		conn.connlock.lock();

		/* If there's bytes queued, and it is different than we last knew, go ahead and return true */
		if (conn.readbuf != null && conn.readbuf.length > 0 && conn.last_parse_len != conn.readbuf.length) {
			conn.connlock.unlock();
			return true;
		}

		try {
			/* Otherwise, we may need to wait for data */
			if (wait_ms == -1) {
				conn.readwait.await();
				retval = true;
			} else if (wait_ms > 0) {
				retval = conn.readwait.await(wait_ms, TimeUnit.MILLISECONDS);
			} else {
				retval = false;
			}
		} catch (java.lang.InterruptedException e) {
			retval = false;
		}

		conn.connlock.unlock();
		return retval;
	}
	
	private Boolean Monitor_write()
	{
		/* Write Data */
		conn.connlock.lock();
		if (conn.writebuf != null && conn.writebuf.length > 0) {
			try {
				conn.outstream.write(conn.writebuf);
			} catch (Exception e) {
				conn.conn_error = "write failure: " + e.getMessage();
				closeConn();
				conn.connlock.unlock();
				return false;
			}
			conn.writebuf = null;
		}
		conn.connlock.unlock();
		return true;
	}
	
	private Boolean Monitor_parse()
	{
		/* Parse */
		conn.connlock.lock();
		while(conn.readbuf != null && conn.readbuf.length > 0) {
			conn.last_parse_len = conn.readbuf.length;

//System.out.println("Checking for response in " + conn.readbuf.length + " bytes");					
			if (conn.readbuf[0] != 0x02) {
				closeConn();
				conn.conn_error = "protocol error, responses must start with STX";
				conn.connlock.unlock();
				return false;
			}

			int etx = byteArrayChr(conn.readbuf, (byte)0x03);
			if (etx == -1) {
//System.out.println("not enough data");					
				/* Not enough data */
				break;
			}
//System.out.println("complete response found");					

			
			/* Chop off txn from readbuf and copy it into txndata */
			byte[] txndata = byteArraySubStr(conn.readbuf, 0, etx);
			if (etx+1 == conn.readbuf.length) {
				conn.readbuf = null;
			} else {
				conn.readbuf = byteArraySubStr(conn.readbuf, etx+1, conn.readbuf.length-(etx+1));
			}

			int fs = byteArrayChr(txndata, (byte)0x1c);
			if (fs == -1) {
				closeConn();
				conn.conn_error = "protocol error, responses must contain a FS";
				conn.connlock.unlock();
				return false;
			}

			long id   = Long.valueOf(byteArrayToString(byteArraySubStr(txndata, 1, fs - 1)));
			byte[] data = byteArraySubStr(txndata, fs+1, txndata.length - fs - 1);

			M_TRAN txn = findtranbyid(id);
			if (txn == null) {
				/* Discarding data */
				continue;
			}

			txn.comma_delimited     = M_verify_comma_delimited(data);
			txn.raw_response        = data;
			data = null;
			if (!txn.comma_delimited) {
				byte[][] lines = byteArrayExplode((byte)'\n', txn.raw_response, (byte)'"', 0);
				if (lines == null || lines.length == 0) {
					closeConn();
					conn.conn_error = "protocol error, response contained no lines";
					conn.connlock.unlock();
					return false;
				}
				for (int i=0; i<lines.length; i++) {
					lines[i] = byteArrayTrim(lines[i]);
					if (lines[i] == null || lines[i].length == 0)
						continue;
					
					byte[][] keyval = byteArrayExplode((byte)'=', lines[i], (byte)0, 2);
					if (keyval == null || keyval.length != 2)
						continue;
					
					String key = byteArrayToString(keyval[0]);
					if (key == null || key.length() == 0)
						continue;
					
					txn.out_params.put(key, M_remove_dupe_quotes(byteArrayTrim(keyval[1])));
				}
			}
			txn.status              = M_TRAN_STATUS_DONE;
		}

		if (conn.readbuf == null || conn.readbuf.length == 0) {
			conn.last_parse_len = 0;
		}
		conn.connlock.unlock();
		return true;
	}


	/*! Perform read/write communications and parse process, and update transactions
	 *  affected.
	 *  \param[in] wait_ms   time in milliseconds to wait for data to be read.
	 *                       -1 means wait forever for data. 0 means do not wait,
	 *                       return immediately, >0 time in milliseconds.
	 * \return 1 on successful read, 0 on error (disconnect)
	 */
	public int Monitor(long wait_ms)
	{
		if (conn.fd == null)
			return 0;

		if (!Monitor_write())
			return 0;

		/* Is there data available to parse? */
		if (Monitor_read(wait_ms)) {
			if (!Monitor_parse())
				return 0;
		}

		return 1;
	}


	public int Monitor()
	{
		return Monitor(0);
	}


	private static String[][] parsecsv(byte[] data, byte delimiter, byte enclosure)
	{
		byte[][] lines = byteArrayExplode((byte)'\n', data, enclosure, 0);
		String[][] csv;
		int line_cnt;

		/* Strip any trailing blank lines */
		for (line_cnt = lines.length; line_cnt > 0 && lines[line_cnt-1].length == 0; line_cnt--) {
			/* Do nothing */
		}

		csv = new String[line_cnt][];

		for (int i=0; i<line_cnt; i++) {
			byte[][] cells = byteArrayExplode(delimiter, lines[i], enclosure, 0);
			csv[i] = new String[cells.length];
			for (int j=0; j<cells.length; j++) {
				csv[i][j] = M_remove_dupe_quotes(byteArrayTrim(cells[j]));
			}
		}
		return csv;
	}

	public int ParseCommaDelimited(long id)
	{
		M_TRAN tran = findtranbyid(id);
		if (tran == null)
			return 0;

		/* Invalid ptr, or transaction has not returned */
		if (tran.status != M_TRAN_STATUS_DONE)
			return 0;

		tran.csv = parsecsv(tran.raw_response, (byte)',', (byte)'"');
		return 1;
	}
	
	public String[][] DuplicateCommaDelimited(long id)
	{
		M_TRAN tran = findtranbyid(id);
		if (tran == null)
			return null;
		
		if (!tran.comma_delimited)
			return null;
			
		String[][] ret = new String[tran.csv.length][tran.csv[0].length];
		
		for (int a=0; a<tran.csv.length; a++)
		{
			System.arraycopy(tran.csv[a],0,ret[a],0,tran.csv[a].length);
		}
		return ret;
	}
	/* ========== LEGACY FUNCTIONS/WRAPPERS ========== */
	
	/* Key definitions for Transaction Parameters */
	public static final String MC_TRANTYPE    = "action";
	public static final String MC_USERNAME    = "username";
	public static final String MC_PASSWORD    = "password";
	public static final String MC_ACCOUNT     = "account";
	public static final String MC_TRACKDATA   = "trackdata";
	public static final String MC_EXPDATE     = "expdate";
	public static final String MC_STREET      = "street";
	public static final String MC_ZIP         = "zip";
	public static final String MC_CV          = "cv";
	public static final String MC_COMMENTS    = "comments";
	public static final String MC_CLERKID     = "clerkid";
	public static final String MC_STATIONID   = "stationid";
	public static final String MC_APPRCODE    = "apprcode";
	public static final String MC_AMOUNT      = "amount";
	public static final String MC_PTRANNUM    = "ptrannum";
	public static final String MC_TTID        = "ttid";
	public static final String MC_USER        = "user";
	public static final String MC_PWD         = "pwd";
	public static final String MC_ACCT        = "acct";
	public static final String MC_BDATE       = "bdate";
	public static final String MC_EDATE       = "edate";
	public static final String MC_BATCH       = "batch";
	public static final String MC_FILE        = "file";
	public static final String MC_ADMIN       = "admin";
	public static final String MC_AUDITTYPE   = "type";
	public static final String MC_CUSTOM      = "custom";
	public static final String MC_EXAMOUNT    = "examount";
	public static final String MC_EXCHARGES   = "excharges";
	public static final String MC_RATE        = "rate";
	public static final String MC_PRIORITY    = "priority";
	public static final String MC_CARDTYPES   = "cardtypes";
	public static final String MC_SUB         = "sub";
	public static final String MC_NEWBATCH    = "newbatch";
	public static final String MC_CURR        = "curr";
	public static final String MC_DESCMERCH   = "descmerch";
	public static final String MC_DESCLOC     = "descloc";
	public static final String MC_PIN         = "pin";
	
	/* Priorities */
	public static final String MC_PRIO_HIGH   = "high";
	public static final String MC_PRIO_NORMAL = "normal";
	public static final String MC_PRIO_LOW    = "low";


	/* Excharges for lodging and auto-rental*/
	public static final String MC_EXCHARGES_REST  = "REST";
	public static final String MC_EXCHARGES_GIFT  = "GIFT";
	public static final String MC_EXCHARGES_MINI  = "MINI";
	public static final String MC_EXCHARGES_TELE  = "TELE";
	public static final String MC_EXCHARGES_OTHER = "OTHER";
	public static final String MC_EXCHARGES_LAUND = "LAUND";
	public static final String MC_EXCHARGES_NONE  = "NONE";
	

	/* Value definitions for Transaction Types */
	public static final String MC_TRAN_SALE            = "sale";
	public static final String MC_TRAN_REDEMPTION      = MC_TRAN_SALE;
	public static final String MC_TRAN_PREAUTH         = "preauth";
	public static final String MC_TRAN_VOID            = "void";
	public static final String MC_TRAN_PREAUTHCOMPLETE = "preauthcomplete";
	public static final String MC_TRAN_FORCE           = "force";
	public static final String MC_TRAN_RETURN          = "return";
	public static final String MC_TRAN_RELOAD          = MC_TRAN_RETURN;
	public static final String MC_TRAN_CREDIT          = MC_TRAN_RETURN;
	public static final String MC_TRAN_SETTLE          = "settle";
	public static final String MC_TRAN_INCREMENTAL     = "incremental";
	public static final String MC_TRAN_REVERSAL        = "reversal";
	public static final String MC_TRAN_ACTIVATE        = "activate";
	public static final String MC_TRAN_BALANCEINQ      = "balanceinq";
	public static final String MC_TRAN_CASHOUT         = "cashout";
	public static final String MC_TRAN_TOREVERSAL      = "toreversal";
	public static final String MC_TRAN_SETTLERFR       = "settlerfr";
	public static final String MC_TRAN_ISSUE           = "issue";
	public static final String MC_TRAN_TIP             = "tip";
	public static final String MC_TRAN_MERCHRETURN     = "merchreturn";
	public static final String MC_TRAN_IVRREQ          = "ivrreq";
	public static final String MC_TRAN_IVRRESP         = "ivrresp";
	public static final String MC_TRAN_ADMIN           = "admin";
	public static final String MC_TRAN_PING            = "ping";
	public static final String MC_TRAN_CHKPWD          = "chkpwd";

	/* Engine Admin Transaction Types */
	public static final String MC_TRAN_CHNGPWD       = "chngpwd";
	public static final String MC_TRAN_LISTSTATS     = "liststats";
	public static final String MC_TRAN_LISTUSERS     = "listusers";
	public static final String MC_TRAN_GETUSERINFO   = "getuserinfo";
	public static final String MC_TRAN_ADDUSER       = "adduser";
	public static final String MC_TRAN_EDITUSER      = "edituser";
	public static final String MC_TRAN_DELUSER       = "deluser";
	public static final String MC_TRAN_ENABLEUSER    = "enableuser";
	public static final String MC_TRAN_DISABLEUSER   = "disableuser";
	public static final String MC_TRAN_IMPORT        = "import";
	public static final String MC_TRAN_EXPORT        = "export";
	public static final String MC_TRAN_ERRORLOG      = "errorlog";
	public static final String MC_TRAN_CLEARERRORLOG = "clearerrorlog";
	public static final String MC_TRAN_GETSUBACCTS   = "getsubaccts";

	/* Value definitions for Admin Types */
	public static final String MC_ADMIN_GUT           = "GUT";
	public static final String MC_ADMIN_GL            = "GL";
	public static final String MC_ADMIN_GFT           = "GFT";
	public static final String MC_ADMIN_BT            = "BT";
	public static final String MC_ADMIN_UB            = MC_ADMIN_BT;
	public static final String MC_ADMIN_QC            = "QC";
	public static final String MC_ADMIN_CTH           = "CTH";
	public static final String MC_ADMIN_CFH           = "CFH";
	public static final String MC_ADMIN_FORCESETTLE   = "FORCESETTLE";
	public static final String MC_ADMIN_SETBATCHNUM   = "SETBATCHNUM";
	public static final String MC_ADMIN_RENUMBERBATCH = "RENUMBERBATCH";
	public static final String MC_ADMIN_FIELDEDIT     = "FIELDEDIT";
	public static final String MC_ADMIN_CLOSEBATCH    = "CLOSEBATCH";

	  /* Response Codes */
	public static final int MCVE_ERROR   = M_ERROR;
	public static final int MCVE_FAIL    = M_FAIL;
	public static final int MCVE_SUCCESS = M_SUCCESS;

	public static final int MCVE_UNUSED  = 0;
	public static final int MCVE_NEW     = 1;
	public static final int MCVE_PENDING = M_PENDING;
	public static final int MCVE_DONE    = M_DONE;

	public static final int MCVE_AUTH    = 2;
	public static final int MCVE_DENY    = 3;
	public static final int MCVE_CALL    = 4;
	public static final int MCVE_DUPL    = 5;
	public static final int MCVE_PKUP    = 6;
	public static final int MCVE_RETRY   = 7;
	public static final int MCVE_SETUP   = 8;
	public static final int MCVE_TIMEOUT = 9;

	/* AVS & CV Response Codes */
	public static final int MCVE_GOOD    = 1;
	public static final int MCVE_BAD     = 0;
	public static final int MCVE_UNKNOWN = -1;

	/* AVS only response codes */
	public static final int MCVE_STREET = 2;
	public static final int MCVE_ZIP    = 3;

	public int TransParam(long identifier, String key, String value)
	{
		return TransKeyVal(identifier, key, value);
	}
	
	public int CustomTransParam(long identifier, String key, String value)
	{
		return TransKeyVal(identifier, key, value);
	}
	
	public int DeleteResponse(long identifier)
	{
		return DeleteTrans(identifier);
	}
	
	public int ReturnCode(long identifier)
	{
		if (IsCommaDelimited(identifier) == 1)
			return M_SUCCESS;
		
		String code = ResponseParam(identifier, "code");
		if (code == null)
			return M_FAIL;
		
		if (code.equalsIgnoreCase("AUTH"))
			return MCVE_AUTH;
		if (code.equalsIgnoreCase("DENY"))
			return MCVE_DENY;
		if (code.equalsIgnoreCase("CALL"))
			return MCVE_CALL;
		if (code.equalsIgnoreCase("PKUP"))
			return MCVE_PKUP;
		if (code.equalsIgnoreCase("DUPL"))
			return MCVE_DUPL;
		if (code.equalsIgnoreCase("RETRY"))
			return MCVE_RETRY;
		if (code.equalsIgnoreCase("SETUP"))
			return MCVE_SETUP;
		if (code.equalsIgnoreCase("TIMEOUT"))
			return MCVE_TIMEOUT;
		return MCVE_FAIL;
	}
	
	public int TransactionItem(long identifier)
	{
		String str = ResponseParam(identifier, "item");
		if (str == null)
			return -1;
		return Integer.valueOf(str);
	}
	
	public int TransactionBatch(long identifier)
	{
		String str = ResponseParam(identifier, "batch");
		if (str == null)
			return -1;
		return Integer.valueOf(str);
	}
	
	public long TransactionID(long identifier)
	{
		String str = ResponseParam(identifier, "ttid");
		if (str == null)
			return -1;
		return Long.parseLong(str, 10);
	}
	
	public int TransactionAVS(long identifier)
	{
		String str = ResponseParam(identifier, "avs");
		if (str == null)
			return MCVE_UNKNOWN;
		if (str.equalsIgnoreCase("GOOD"))
			return MCVE_GOOD;
		if (str.equalsIgnoreCase("BAD"))
			return MCVE_BAD;
		if (str.equalsIgnoreCase("ZIP"))
			return MCVE_ZIP;
		if (str.equalsIgnoreCase("STREET"))
			return MCVE_STREET;
		return MCVE_UNKNOWN;
	}
	
	public int TransactionCV(long identifier)
	{
		String str = ResponseParam(identifier, "cv");
		if (str == null)
			return MCVE_UNKNOWN;
		if (str.equalsIgnoreCase("GOOD"))
			return MCVE_GOOD;
		if (str.equalsIgnoreCase("BAD"))
			return MCVE_BAD;
		return MCVE_UNKNOWN;
	}
	
	public String TransactionAuth(long identifier)
	{
		return ResponseParam(identifier, "auth");
	}
	
	public String TransactionText(long identifier)
	{
		return ResponseParam(identifier, "verbiage");
		
	}
	
	public String TEXT_Code(int code)
	{
		switch(code) {
		case MCVE_AUTH:
			return "AUTH";
		case MCVE_DENY:
			return "DENY";
		case MCVE_CALL:
			return "CALL";
		case MCVE_DUPL:
			return "DUPL";
		case MCVE_RETRY:
			return "RETRY";
		case MCVE_SETUP:
			return "SETUP";
		case MCVE_TIMEOUT:
			return "TIMEOUT";
		case MCVE_SUCCESS:
			return "SUCCESS";
		case MCVE_FAIL:
			return "FAIL";
		}
		return "FAIL";
	}
	
	public String TEXT_AVS(int code)
	{
		switch(code) {
		case MCVE_STREET:
			return "STREET";
		case MCVE_ZIP:
			return "ZIP";
		case MCVE_GOOD:
			return "GOOD";
		case MCVE_BAD:
			return "BAD";
		}
		return "UNKNOWN";
	}
	
	public String TEXT_CV(int code)
	{
		return TEXT_AVS(code);
	}

	public long Sale(String username, String password, String trackdata, String account, String expdate, double amount,
			String street, String zip, String cv, String comments, String clerkid, String stationid, int ptrannum)
	{
		long id = TransNew();
		TransKeyVal(id, "action", "sale");
		
		TransKeyVal(id, "username", username);
		TransKeyVal(id, "password", password);
		if (trackdata != null && trackdata.length() > 0)
			TransKeyVal(id, "trackdata", trackdata);
		if (account != null && account.length() > 0)
			TransKeyVal(id, "account", account);
		if (expdate != null && expdate.length() > 0)
			TransKeyVal(id, "expdate", expdate);
		TransKeyVal(id, "amount", Double.toString(amount));
		if (street != null && street.length() > 0)
			TransKeyVal(id, "street", street);
		if (zip != null && zip.length() > 0)
			TransKeyVal(id, "zip", zip);
		if (cv != null && cv.length() > 0)
			TransKeyVal(id, "cv", cv);
		if (comments != null && comments.length() > 0)
			TransKeyVal(id, "comments", comments);
		if (clerkid != null && clerkid.length() > 0)
			TransKeyVal(id, "clerkid", clerkid);
		if (stationid != null && stationid.length() > 0)
			TransKeyVal(id, "stationid", stationid);
		if (ptrannum > 0)
			TransKeyVal(id, "ptrannum", Integer.toString(ptrannum));
		if (TransSend(id) == 0) {
			DeleteTrans(id);
			return -1;
		}
		return id;
	}
	
	public long PreAuth(String username, String password, String trackdata, String account, String expdate, double amount,
			String street, String zip, String cv, String comments, String clerkid, String stationid, int ptrannum)
	{
		long id = TransNew();
		TransKeyVal(id, "action", "preauth");
		
		TransKeyVal(id, "username", username);
		TransKeyVal(id, "password", password);
		if (trackdata != null && trackdata.length() > 0)
			TransKeyVal(id, "trackdata", trackdata);
		if (account != null && account.length() > 0)
			TransKeyVal(id, "account", account);
		if (expdate != null && expdate.length() > 0)
			TransKeyVal(id, "expdate", expdate);
		TransKeyVal(id, "amount", Double.toString(amount));
		if (street != null && street.length() > 0)
			TransKeyVal(id, "street", street);
		if (zip != null && zip.length() > 0)
			TransKeyVal(id, "zip", zip);
		if (cv != null && cv.length() > 0)
			TransKeyVal(id, "cv", cv);
		if (comments != null && comments.length() > 0)
			TransKeyVal(id, "comments", comments);
		if (clerkid != null && clerkid.length() > 0)
			TransKeyVal(id, "clerkid", clerkid);
		if (stationid != null && stationid.length() > 0)
			TransKeyVal(id, "stationid", stationid);
		if (ptrannum > 0)
			TransKeyVal(id, "ptrannum", Integer.toString(ptrannum));
		if (TransSend(id) == 0) {
			DeleteTrans(id);
			return -1;
		}
		return id;
	}
	/*
	public int Void(String username, String password, int sid, int ptrannum)
	public int PreAuthCompletion(String username, String password, double finalamount, int sid, int ptrannum)
	public int Force(String username, String password, String trackdata, String account, String expdate, double amount, String authcode, String comments, String clerkid, String stationid, int ptrannum)
	public int Return( String username, String password, String trackdata, String account, String expdate, double amount, String comments, String clerkid, String stationid, int ptrannum)
	public int Override(String username, String password, String trackdata, String account, String expdate, double amount, String street, String zip, String cv, String comments, String clerkid, String stationid, int ptrannum)
	public int Settle(String username, String password, String batch)
	public int Ub(String username, String password)
	public int Bt(String username, String password)
	public int Gft(String username, String password, int type, String acct, String clerkid, String stationid, String comments, double ptrannum, String bdate, String
	public int Gl(String username, String password, int type, String acct, String batch, String clerkid, String stationid, String comments, double ptrannum, String bdate, String edate)
	public int Gut(String username, String password, int type, String acct, String clerkid, String stationid, String comments, double ptrannum, String bdate, String edate)
	public long Ping()
	public String SSLCert_gen_hash(String filename);
*/
}
