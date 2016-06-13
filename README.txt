LIBMONETRA JAVA v0.9.4

================
Revision History
================
 * 0.9.0 - Initial Public Release
 * 0.9.1 - M_SetSSL_Files -> SetSSL_Files
           don't set a transaction to done until after it is fully parsed
 * 0.9.2 - Instead of a dead wait in verifyping() and TransSend(), use a
           conditional to be immediately notified of when data is available.
           This should reduce latency.
 * 0.9.3 - Fix CompleteAuthorizations()
 * 0.9.4 - Fix parser, encapsulation character was set as comma instead of a
           double quote
 * 0.9.5 - Fix large response handling

========
Overview
========
This library attempts to emulate the Java Monetra JNI module as closely as possible
(which wraps the C-based libmonetra library).

This implementation is in 100% JAVA using only standard Java APIs found in JDK 1.5
and higher so provides much more portability.  No more need to recompile the JNI
module for every OS you wish to deploy on.

This library is designed to be Thread-Safe, but has not yet been extensively tested
as such.

=====
FILES
=====
 - MONETRA.java   - This is the entire implementation of the class, it is in the
                    com.mainstreetsoftworks namespace.
 - MCVE.java      - This is a wrapper class for legacy users.
 - Base64.java    - Base64 helper library used by MONETRA.java for submitting binary
                    data to Monetra.  Original source: http://migbase64.sourceforge.net/
 - Makefile       - Standard unix make file to assist with building the MONETRA.jar
 - tests/monetratest.java  - Internal test cases during development of the library. Provides
                             some nice tests such as a Threaded test and a disconnect test.
 - tests/test_stdapi.java  - Typical test, formerly known as AppDemo.java
 - tests/test_oldapi.java  - Test using legacy functions, formerly known as AppDemo2.java
 - tests/test_guidemo.java - Test using legacy functions and showing a gui, formerly
                             known as GUIAppDemo.java

========
Building
========
 - Requires JDK 1.5 or higher
 - If on a Unix system, should simply be able to run 'make', Windows systems may need to
   come up with their own build method.


=================
Using the library
=================
 * Add 'MONETRA.jar' to your classpath
 * import com.mainstreetsoftworks
 * Use the library!


====
TODO
====
 - Unit test
 - The 'cafile' specified by M_SetSSL_CAfile() is not honored, the default system
   certificate store is used instead.
 - The client certificates as provided by M_SetSSL_Files() are not currently honored.

