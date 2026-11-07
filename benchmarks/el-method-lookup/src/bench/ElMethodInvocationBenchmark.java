package bench;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import jakarta.el.ELContext;
import jakarta.el.ExpressionFactory;
import jakarta.el.StandardELContext;
import jakarta.el.ValueExpression;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Evaluates pre-parsed EL expressions that invoke a method of a class with 200 public methods.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(2)
public class ElMethodInvocationBenchmark {

    private ELContext context;
    private ValueExpression withArgument;
    private ValueExpression overloaded;
    private ValueExpression noArgument;
    private ValueExpression listSize;

    @Setup
    public void setup() {
        ExpressionFactory factory = ExpressionFactory.newInstance();
        StandardELContext ctx = new StandardELContext(factory);
        ctx.getVariableMapper().setVariable("big", factory.createValueExpression(new Big(), Big.class));
        ctx.getVariableMapper().setVariable("s", factory.createValueExpression("x", String.class));
        ctx.getVariableMapper().setVariable("list", factory.createValueExpression(new ArrayList<String>(), List.class));
        context = ctx;
        withArgument = factory.createValueExpression(ctx, "${big.m150(s)}", Object.class);
        overloaded = factory.createValueExpression(ctx, "${big.over(s)}", Object.class);
        noArgument = factory.createValueExpression(ctx, "${big.noArg()}", Object.class);
        listSize = factory.createValueExpression(ctx, "${list.size() > 0}", Object.class);
    }

    @Benchmark
    public Object methodWithArgument() {
        return withArgument.getValue(context);
    }

    @Benchmark
    public Object overloadedMethod() {
        return overloaded.getValue(context);
    }

    @Benchmark
    public Object methodWithoutArgument() {
        return noArgument.getValue(context);
    }

    @Benchmark
    public Object listSizeComparison() {
        return listSize.getValue(context);
    }
}
