[![License](https://img.shields.io/badge/license-Apache%20License%202.0-blue.svg?logo=apache&style=flat-square)](https://www.apache.org/licenses/LICENSE-2.0)
[![Version](https://img.shields.io/maven-central/v/dev.jayo/jayo-http?logo=apache-maven&color=&style=flat-square)](https://central.sonatype.com/artifact/dev.jayo/jayo)
[![Java](https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white&style=flat-square)](https://www.java.com/en/download/help/whatis_java.html)
[![Kotlin](https://img.shields.io/badge/kotlin-2.3.0-blue.svg?logo=kotlin&style=flat-square)](http://kotlinlang.org)

# Jayo HTTP

A fast synchronous HTTP library based on Jayo for the JVM.

Synchronous APIs are easier to work with than asynchronous and non-blocking APIs; the code is easier to write, easier to
read, and easier to debug (with stack traces that make sense!).

```java
private final JayoHttpClient client = JayoHttpClient.create();

public void run() {
  ClientRequest request = ClientRequest.builder()
    .url("https://raw.githubusercontent.com/jayo-projects/jayo-http/main/samples/src/main/resources/jayo-http.txt")
    .get();

  try (ClientResponse response = client.newCall(request).execute()) {
    if (!response.isSuccessful()) {
      throw new JayoException("Unexpected code " + response.getStatusCode());
    }

    Headers responseHeaders = response.getHeaders();
    for (int i = 0; i < responseHeaders.size(); i++) {
      System.out.println(responseHeaders.name(i) + ": " + responseHeaders.value(i));
    }

    System.out.println(response.getBody().string());
  }
}
```

Jayo HTTP is available on Maven Central.

Gradle:
```groovy
dependencies {
    implementation("dev.jayo:jayo-http:X.Y.Z")
}
```

Maven:
```xml

<dependency>
    <groupId>dev.jayo</groupId>
    <artifactId>jayo-http</artifactId>
    <version>X.Y.Z</version>
</dependency>
```

Jayo HTTP is written in Java without the use of any external dependencies, to be as light as possible.

We also love Kotlin ! Jayo HTTP is fully usable and optimized from Kotlin code thanks to JSpecify nullability
annotations, Kotlin friendly method naming (`get*` and `set*`) and Kotlin extension functions are natively included in
this project.

Jayo HTTP's source code is derived and inspired from [OkHttp](https://github.com/square/okhttp), but does not preserve
backward compatibility with it.

See the project website (*coming soon*) for documentation and APIs.

Jayo HTTP requires Java 17 or more recent.

*Contributions are very welcome, simply clone this repo and submit a PR when your fix, new feature, or optimization is
ready!*

## Build

You need a JDK 25 to build Jayo HTTP.

1. Clone this repo

```bash
git clone git@github.com:jayo-projects/jayo-http.git
```

2. Build the project

```bash
./gradlew clean build
```

## License

[Apache-2.0](https://opensource.org/license/apache-2-0)

Copyright (c) 2024-present, pull-vert and Jayo contributors
