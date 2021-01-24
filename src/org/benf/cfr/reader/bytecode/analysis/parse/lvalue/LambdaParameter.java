package org.benf.cfr.reader.bytecode.analysis.parse.lvalue;

import org.benf.cfr.reader.bytecode.analysis.types.MethodPrototype;
import org.benf.cfr.reader.bytecode.analysis.types.discovery.InferredJavaType;
import org.benf.cfr.reader.util.output.Dumper;

public class LambdaParameter extends LocalVariable {
    public final MethodPrototype originalMethod;
    public final int index; // index in method params, not slot

    public LambdaParameter(String name, InferredJavaType inferredJavaType, MethodPrototype originalMethod, int index) {
        super(name, inferredJavaType);
        this.index = index;
        this.originalMethod = originalMethod;
    }

    @Override
    public Dumper dump(Dumper d, boolean defines) {
        getName().dumpParameter(d, originalMethod, index, defines);
        return d;
    }
}
