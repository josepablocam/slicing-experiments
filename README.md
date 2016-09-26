Building WALA
--------------------
You should have git, mvn, and java 8 installed on your machine.

Go to 

http://wala.sourceforge.net/wiki/index.php/UserGuide:Getting_Started

for more information. Steps reproduced below

```
josecambronero ~$ git clone https://github.com/wala/WALA.git
```

You also need to add the `dx.jar` from Android to the appropriate subdirectory
in the WALA directory as explained in

https://groups.google.com/forum/#!msg/wala-sourceforge-net/cBYsfEvYVG0/Ua52dyQQU-YJ

We found it easier to simply download the jar from maven and
put it where we needed it.


https://mvnrepository.com/artifact/com.google.android.tools/dx/1.7

```
josecambronero WALA$ cd com.ibm.wala.dalvik.test/
josecambronero com.ibm.wala.dalvik.test$ mkdir lib
josecambronero com.ibm.wala.dalvik.test$ cd lib
josecambronero lib$ wget http://central.maven.org/maven2/com/google/android/tools/dx/1.7/dx-1.7.jar dx.jar
josecambronero lib$ mv dx-1.7.jar dx.jar
```

Then in the main WALA directory

```
josecambronero WALA$ jenv init
josecambronero WALA$ java -version
java version "1.8.0_71"
Java(TM) SE Runtime Environment (build 1.8.0_71-b15)
Java HotSpot(TM) 64-Bit Server VM (build 25.71-b15, mixed mode)
josecambronero WALA$ mvn clean
josecambronero WALA$ mvn install -DskipTests=true
```

We run `install` above rather than `verify` (as done in the wiki) to install
to our local maven repository (so we can include this easily as a dependency
in our project).

You should now be able to confirm that the appropriate WALA jars were placed
into your local repository

```
josecambronero WALA$ ls ~/.m2/repository/com/ibm/wala/ | head -n 3
WALA
com.ibm.wala-feature
com.ibm.wala-repository
```

Building experiments
--------------------
We assume you have built WALA as done above.

```
josecambronero$ cd slicing-experiments
josecambronero slicing-experiments$ mvn package
```

You can now run forward slicing on a given class + source.

```
java -cp target/slicing-1.0-SNAPSHOT-jar-with-dependencies.jar slicing.SimpleSlicer <jar> <caller> <callee> <analysis>
```

For example, consider the file Example.java below

```
josecambronero slicing-experiments$ cat Example.java 
class Example {
  public static String hi(String nm) {
    return "hi " + nm;
  }
  
  public static String bye(String nm) {
    return "bye " + nm;
  }
  
  public static void main(String[] args) {
    String myHi = hi("you");
    String myBye = bye("you");
    String complete = myBye + "!";
  }
}
```

We compile it and place it into a jar
```
javac Example.java
jar cf example.jar Example.class
```

Make sure you use the appropriate jar, as the one referenced above includes
all additional necessary dependencies (namely, WALA).

```
josecambronero slicing-experiments$ java -cp target/slicing-1.0-SNAPSHOT-jar-with-dependencies.jar slicing.SimpleSlicer example.jar "Example.main([Ljava/lang/String;)V" "Example.bye(Ljava/lang/String;)Ljava/lang/String;" 0cfa
===> Computing slice
===> Done with slice
NORMAL_RET_CALLER:Node: < Application, LExample, main([Ljava/lang/String;)V > Context: Everywhere[4]7 = invokestatic < Application, LExample, bye(Ljava/lang/String;)Ljava/lang/String; > 3 @8 exception:6
NORMAL main:11 = invokevirtual < Application, Ljava/lang/StringBuilder, append(Ljava/lang/String;)Ljava/lang/StringBuilder; > 8,7 @20 exception:10 Node: < Application, LExample, main([Ljava/lang/String;)V > Context: Everywhere
PARAM_CALLER:Node: < Application, LExample, main([Ljava/lang/String;)V > Context: Everywhere[10]11 = invokevirtual < Application, Ljava/lang/StringBuilder, append(Ljava/lang/String;)Ljava/lang/StringBuilder; > 8,7 @20 exception:10 v7
EXC_RET_CALLER:Node: < Application, LExample, main([Ljava/lang/String;)V > Context: Everywhere[10]11 = invokevirtual < Application, Ljava/lang/StringBuilder, append(Ljava/lang/String;)Ljava/lang/StringBuilder; > 8,7 @20 exception:10
HEAP_RET_CALLER:Node: < Application, LExample, main([Ljava/lang/String;)V > Context: Everywhere [[<Primordial,Ljava/lang/String>],< Primordial, Ljava/lang/String, value, <Primordial,[C> >] call:11 = invokevirtual < Application, Ljava/lang/StringBuilder, append(Ljava/lang/String;)Ljava/lang/StringBuilder; > 8,7 @20 exception:10

	0cfa: 9757 ms
```
