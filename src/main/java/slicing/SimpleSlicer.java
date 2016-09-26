/*
Sourced from http://wala.sourceforge.net/wiki/index.php/UserGuide:Slicer
and slightly modified

Original sources can be found at
https://github.com/SCanDroid/WALA/blob/master/com.ibm.wala.core.tests/src/com/ibm/wala/examples/drivers/PDFSlice.java
https://github.com/wala/WALA/blob/master/com.ibm.wala.core.tests/src/com/ibm/wala/examples/drivers/PDFWalaIR.java
*/
package slicing;

import java.io.*;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import com.ibm.wala.ipa.callgraph.CallGraphBuilder;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.slicer.NormalReturnCaller;
import com.ibm.wala.ipa.slicer.NormalStatement;
import com.ibm.wala.ipa.slicer.Slicer;
import com.ibm.wala.ipa.slicer.Slicer.ControlDependenceOptions;
import com.ibm.wala.ipa.slicer.Slicer.DataDependenceOptions;
import com.ibm.wala.ipa.slicer.Statement;
import com.ibm.wala.ipa.slicer.Statement.Kind;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.config.AnalysisScopeReader;
import com.ibm.wala.util.debug.Assertions;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.strings.StringStuff;

public class SimpleSlicer {

    private static final int PRINT_LIMIT = 5;
    // this is a place where we could cut things down to improve scalability
    private static File EXCLUSIONS = getExclusionsFile();

    /**
     * Build call graph builder with specific analysis algorithm
     * @param analysis
     * @param options
     * @param cache
     * @param cha
     * @param scope
     * @return
     */
    public static CallGraphBuilder makeCallGraphBuilder(
            String analysis,
            AnalysisOptions options,
            AnalysisCache cache,
            ClassHierarchy cha,
            AnalysisScope scope) {

        CallGraphBuilder builder = null;

        switch(analysis) {
            case "0cfa":
                builder = Util.makeZeroCFABuilder(options, cache, cha, scope, null, null);
                break;
            case "vanilla-1cfa":
                builder = Util.makeVanillaZeroOneCFABuilder(options, cache, cha, scope, null, null);
                break;
            case "container-1cfa":
                builder = Util.makeZeroOneContainerCFABuilder(options, cache, cha, scope, null, null);
                break;
            default:
                System.out.println("Unknown analysis");
                System.exit(1);
        }

        return builder;
    }

    /**
     * Run forward slicing
     * @param appJar
     * @param srcCaller
     * @param srcCallee
     * @param analysis
     */
    public static void slice(String appJar, String srcCaller, String srcCallee, String analysis) {
        try {
            // naive timing, but fine for example purposes
            final long startTime = System.currentTimeMillis();

            AnalysisScope scope = AnalysisScopeReader.makeJavaBinaryAnalysisScope(appJar, EXCLUSIONS);

            // create class hierarchy, wala needs to know the lay of the land
            ClassHierarchy cha = ClassHierarchy.make(scope);
            Iterable<Entrypoint> entrypoints = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(scope, cha);
            AnalysisOptions options = new AnalysisOptions(scope, entrypoints);

            // create method references
            MethodReference srcCallerRef = StringStuff.makeMethodReference(srcCaller);
            MethodReference srcCalleeRef = StringStuff.makeMethodReference(srcCallee);

            // build the call graph for entire jar (in reality this would likely be done just for the class)
            CallGraphBuilder builder = makeCallGraphBuilder(analysis, options, new AnalysisCache(), cha, scope);
            CallGraph cg = builder.makeCallGraph(options, null);

            // data and control flow dependencies (for reachability) in slicing
            DataDependenceOptions dataOptions = DataDependenceOptions.FULL;
            ControlDependenceOptions controlOptions = ControlDependenceOptions.FULL;

            // find location of first statement that calls srcCallee
            CGNode callerNode = findMethod(cg, srcCallerRef);
            Statement stmt = findCallTo(callerNode, srcCalleeRef);
            stmt = getReturnStatementForCall(stmt);

            // collect slice forward
            Collection<Statement> slice = null;
            PointerAnalysis pa = builder.getPointerAnalysis();
            System.out.println("===> Computing slice");
            slice = Slicer.computeForwardSlice(stmt, cg, pa, dataOptions, controlOptions);
            System.out.println("===> Done with slice");

            // note that the two print statements above are factoring into this time
            final long endTime = System.currentTimeMillis();

            dumpSlice(slice, PRINT_LIMIT);

            report(analysis, endTime - startTime);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // get exclusions for analysis
    public static File getExclusionsFile() {
        // read file from jar and put it into a temp file
        try {
            InputStream resource =
                    SimpleSlicer.class.getClassLoader().getResourceAsStream("exclusions.txt");
            File tempfile = File.createTempFile("exclusions", ".txt");
            OutputStream out = new FileOutputStream(tempfile);
            int read = 0;
            byte[] data = new byte[1024];

            while((read = resource.read(data)) != -1) {
                out.write(data, 0, read);
            }
            tempfile.deleteOnExit();
            return tempfile;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    // find node for method in call graph
    public static CGNode findMethod(CallGraph cg, MethodReference method) {
        for (Iterator<? extends CGNode> it = cg.iterator(); it.hasNext();) {
            CGNode n = it.next();
            if (n.getMethod().getReference().equals(method)) {
                return n;
            }
        }
        System.err.println("call graph " + cg);
        Assertions.UNREACHABLE("failed to find method " + method.toString());
        return null;
    }

    // modification of original to use method reference
    // find call to method in a particular node in call graph
    public static Statement findCallTo(CGNode n, MethodReference method) {
        IR ir = n.getIR();
        for (Iterator<SSAInstruction> it = ir.iterateAllInstructions(); it.hasNext();) {
            SSAInstruction s = it.next();
            if (s instanceof SSAInvokeInstruction) {
                SSAInvokeInstruction call = (SSAInvokeInstruction) s;
                if (call.getCallSite().getDeclaredTarget().equals(method)) {
                    IntSet indices = ir.getCallInstructionIndices(((SSAInvokeInstruction) s).getCallSite());
                    Assertions.productionAssertion(indices.size() == 1, "expected 1 but got " + indices.size());
                    return new NormalStatement(n, indices.intIterator().next());
                }
            }
        }
        Assertions.UNREACHABLE("failed to find call to " + method.toString() + " in " + n);
        return null;
    }

    // modification of original to use method reference
    // get return statement associated with call
    public static Statement getReturnStatementForCall(Statement s) {
        if (s.getKind() == Kind.NORMAL) {
            NormalStatement n = (NormalStatement) s;
            SSAInstruction st = n.getInstruction();
            if (st instanceof SSAInvokeInstruction) {
                SSAAbstractInvokeInstruction call = (SSAAbstractInvokeInstruction) st;
                if (call.getCallSite().getDeclaredTarget().getReturnType().equals(TypeReference.Void)) {
                    throw new IllegalArgumentException("this driver computes forward slices from the return value of calls.\n" + ""
                            + "Method " + call.getCallSite().getDeclaredTarget().getSignature() + " returns void.");
                }
                return new NormalReturnCaller(s.getNode(), n.getInstructionIndex());
            } else {
                return s;
            }
        } else {
            return s;
        }
    }

    /**
     * Print out statements to stdout (up to limit # of statements)
     * @param slice
     * @param limit
     */
    public static void dumpSlice(Collection<Statement> slice, int limit) {
        Iterator<Statement> s = slice.iterator();
        for (int i = 0; i < limit && s.hasNext(); i++) {
            System.out.println(s.next());
        }
    }

    /**
     * Provide reporting of execution time
     * @param analysis
     * @param ms
     */
    public static void report(String analysis, long ms) {
        System.out.println("\n\t" + analysis + ": " + ms + " ms");
    }

    /**
     * Help message
     */
    public static void help() {
        System.out.println(
                "Usage:java -jar slicer.java slicing.Slicer <target-jar-path> <caller-sig> <callee-sig> <analysis>\n" +
                "Method signatures should correspond to that found in the bytecode (javap -s)\n" +
                "Analysis must be one of: 0cfa, vanilla-1cfa, container-1cfa\n" +
                "For example:\n" +
                "slicing.Slicer example.jar 'Example.main([Ljava/lang/String;)V;' 'Example.bye(Ljava/lang/String;)Ljava/lang/String;' 0cfa\n"
        );
    }

    /**
     * Run experiment on a given jar + caller + callee + analysis
     * @param args
     */
    public static void main(String[] args) {
        List<String> analysisNames = Arrays.asList("0cfa", "vanilla-1cfa", "container-1cfa");
        if (args.length != 4) {
            help();
            System.exit(1);
        }

        String jarPath = args[0];
        String srcCaller = args[1];
        String srcCallee = args[2];
        String analysis = args[3];

        if (!analysisNames.contains(analysis)) {
            help();
            System.exit(1);
        }

        slice(jarPath, srcCaller, srcCallee, analysis);
    }
}