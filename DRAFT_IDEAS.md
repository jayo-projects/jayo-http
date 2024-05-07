## Some inspirations

* [Helidon Nima](https://helidon.io/docs/v4/about/doc_overview) is a JDK 21+ web server running on virtual threads
* [Channel per-stream for HTTP2 in Netty](https://github.com/netty/netty/pull/11603)
* [TLS implementation for NIO](https://github.com/marianobarrios/tls-channel)
* [Javalin](https://javalin.io/) is a simple web framework for Java and Kotlin
* [java-http](https://github.com/FusionAuth/java-http) full-featured HTTP server and client in Java without the use of
any libraries, based on non-blocking NIO. It handles connections via the selector. Connections don't back up and client
connection pools can always be re-used with Keep-Alive.

### Reminder : Kotlin operators :
For performing bitwise operations, Kotlin provides following methods that work for Int and Long types -
* shl - signed shift left (equivalent of << operator)
* shr - signed shift right (equivalent of >> operator)
* ushr - unsigned shift right (equivalent of >>> operator)
* and - bitwise and (equivalent of & operator)
* or - bitwise or (equivalent of | operator)
* xor - bitwise xor (equivalent of ^ operator)
* inv - bitwise complement (equivalent of ~ operator)
