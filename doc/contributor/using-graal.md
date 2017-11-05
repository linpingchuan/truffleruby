# Using TruffleRuby with Graal for Contributors

The easiest way for end-users to get the Graal compiler and a compatible JVM is
with the GraalVM. We recommend that they use the version of TruffleRuby which is
bundled in GraalVM.

For contributors, the version of Graal and Truffle in
GraalVM is not always compatible, so we recommend building Graal yourself for
developing TruffleRuby. See [Building Graal](building-graal.md).

If you want to run TruffleRuby on a GraalVM release anyway, first follow the
instructions for end-users in [Running with GraalVM](../user/using-graalvm.md).
Then set the environment variable `GRAALVM_BIN` to the `bin/java` executable in
GraalVM and use `jt` to run TruffleRuby.

```
$ GRAALVM_BIN=..../bin/java jt ruby --graal ...
```
