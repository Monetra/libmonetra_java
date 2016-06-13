# $Id: Makefile.in 12682 2008-01-24 15:53:55Z chris $

JH         =
ifneq (${JAVA_HOME},)
	JH = ${JAVA_HOME}/bin/
endif

JAVAC      = ${JH}javac
JAR        = ${JH}jar
JAVA       = ${JH}java

all: MCVE.jar tests/monetratest.class tests/test_stdapi.class tests/test_oldapi.class tests/test_guidemo.class
test: monetratest stdapitest oldapitest guidemotest

com/mainstreetsoftworks/Base64.class: Base64.java
	${JAVAC} Base64.java -classpath . -d .

com/mainstreetsoftworks/MONETRA.class: MONETRA.java com/mainstreetsoftworks/Base64.class
	${JAVAC} MONETRA.java -classpath . -d .

MONETRA.jar: com/mainstreetsoftworks/MONETRA.class com/mainstreetsoftworks/Base64.class
	${JAR} cf MONETRA.jar com

com/mainstreetsoftworks/MCVE.class: MONETRA.jar
	${JAVAC} MCVE.java -classpath . -d .

MCVE.jar: com/mainstreetsoftworks/MCVE.class
	${JAR} cf MCVE.jar com

tests/monetratest.class: MONETRA.jar tests/monetratest.java
	${JAVAC} -classpath MONETRA.jar tests/monetratest.java

tests/test_stdapi.class: MONETRA.jar tests/test_stdapi.java
	${JAVAC} -classpath MONETRA.jar tests/test_stdapi.java

tests/test_oldapi.class: MCVE.jar tests/test_oldapi.java
	${JAVAC} -classpath MCVE.jar tests/test_oldapi.java

tests/test_guidemo.class: MCVE.jar tests/test_guidemo.java
	${JAVAC} -classpath MCVE.jar tests/test_guidemo.java

monetratest: tests/monetratest.class
	${JAVA} -cp "./MONETRA.jar:./tests" monetratest

stdapitest: tests/test_stdapi.class
	${JAVA} -cp "./MONETRA.jar:./tests" test_stdapi

oldapitest: tests/test_oldapi.class
	${JAVA} -cp "./MCVE.jar:./tests" test_oldapi

guidemotest: tests/test_guidemo.class
	${JAVA} -cp "./MCVE.jar:./tests" test_guidemo

clean:
	rm -f *.class *.jar tests/*.class
	rm -rf com

install:
