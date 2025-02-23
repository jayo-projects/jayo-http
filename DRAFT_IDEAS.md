## Some inspirations

* [ktor-io](https://github.com/ktorio/ktor) is a Kotlin only I/O Web framework based on coroutines
* [Helidon Nima](https://helidon.io/docs/v4/about/doc_overview) is a JDK 21+ web server running on virtual threads
* [Channel per-stream for HTTP2 in Netty](https://github.com/netty/netty/pull/11603)
* [Javalin](https://javalin.io/) is a simple web framework for Java and Kotlin
* [java-http](https://github.com/FusionAuth/java-http) full-featured HTTP server and client in plain Java without the
use of any libraries. The client and server will use Project Loom virtual threads and blocking I/O so that the Java VM
will handle all the context switching between virtual threads as they block on I/O.
* [Unirest](https://kong.github.io/unirest-java/) is intended to be a simple and obvious library for HTTP requests. It
provides a fluent interface that makes discovery of commands easy in a modern IDE.

### Reminder : Kotlin operators :
For performing bitwise operations, Kotlin provides the following methods that work for Int and Long types
* shl - signed shift left (equivalent of << operator)
* shr - signed shift right (equivalent of >> operator)
* ushr - unsigned shift right (equivalent of >>> operator)
* and - bitwise and (equivalent of & operator)
* or - bitwise or (equivalent of | operator)
* xor - bitwise xor (equivalent of ^ operator)
* inv - bitwise complement (equivalent of ~ operator)

### Code improvements ideas
* [Bubble sort for media types](https://poutsma-principles.com/blog/2025/04/29/bubble-sort-in-spring) Efficiency is
important, but sometimes correctness matters even more!
