# JMH benchmark for the EL method lookup cache (BZ 70241)

This benchmark measures the evaluation of pre-parsed EL expressions that invoke a method of a class with 200 public
methods. It was used for https://github.com/apache/tomcat/pull/1155 (https://bz.apache.org/bugzilla/show_bug.cgi?id=70241).

It is not part of the Tomcat build because Tomcat does not use JMH. It only needs the EL jars of the build being
measured: `el-api.jar`, `jasper-el.jar` and `tomcat-juli.jar`.

## Benchmarks

| Benchmark | Expression |
|---|---|
| methodWithArgument | `${big.m150(s)}` |
| overloadedMethod | `${big.over(s)}`, three overloads |
| methodWithoutArgument | `${big.noArg()}` |
| listSizeComparison | `${list.size() > 0}` |

## How to run

Build Tomcat twice with `ant deploy`, once from `apache/tomcat` main (commit `7cdebe9eaa`, "before") and once from the
branch of the pull request (commit `2b92fbf498`, "after"). Then compile the benchmark once and run it against the
`output/build/lib` directory of each build.

```
M=~/.m2/repository
JMH=$M/org/openjdk/jmh/jmh-core/1.37/jmh-core-1.37.jar
ANN=$M/org/openjdk/jmh/jmh-generator-annprocess/1.37/jmh-generator-annprocess-1.37.jar
OPT=$M/net/sf/jopt-simple/jopt-simple/5.0.4/jopt-simple-5.0.4.jar
MATH=$M/org/apache/commons/commons-math3/3.6.1/commons-math3-3.6.1.jar

javac -d out -cp $JMH:$OPT:$MATH:$LIB_BEFORE/el-api.jar -processorpath $ANN:$JMH:$OPT:$MATH src/bench/*.java

for LIB in $LIB_BEFORE $LIB_AFTER; do
  java -cp out:$JMH:$OPT:$MATH:$LIB/el-api.jar:$LIB/jasper-el.jar:$LIB/tomcat-juli.jar \
      org.openjdk.jmh.Main bench.ElMethodInvocationBenchmark -prof gc
done
```

The benchmark class has 2 forks, each with 5 warm up and 8 measurement iterations of 1 second. The GC profiler reports
the bytes allocated for each operation (`gc.alloc.rate.norm`).

## Results

JDK 21, JMH 1.37. The raw output is in `results-before.txt` and `results-after.txt`.

| Expression | Before ns/op | After ns/op | Faster | Before B/op | After B/op | Less allocation |
|---|---|---|---|---|---|---|
| `${big.m150(s)}` | 4189 +/- 102 | 627 +/- 10 | 6.7x | 19976 | 360 | 98% |
| `${big.over(s)}`, 3 overloads | 4390 +/- 169 | 634 +/- 9 | 6.9x | 20008 | 392 | 98% |
| `${big.noArg()}` | 463 +/- 34 | 443 +/- 18 | 1.0x | 264 | 264 | 0% |
| `${list.size() > 0}` | 196 +/- 37 | 189 +/- 3 | 1.0x | 264 | 264 | 0% |
