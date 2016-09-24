/*
Sourced from http://wala.sourceforge.net/wiki/index.php/UserGuide:Slicer
and slightly modified

Original source can be found at
https://github.com/SCanDroid/WALA/blob/master/com.ibm.wala.core.tests/src/com/ibm/wala/examples/drivers/PDFSlice.java
*/
package slicing;

import java.io.File;
import java.util.Collection;

import com.ibm.wala.core.tests.callGraph.CallGraphTestUtil;
import com.ibm.wala.ipa.callgraph.CallGraphBuilder;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.slicer.NormalReturnCaller;
import com.ibm.wala.ipa.slicer.NormalStatement;
import com.ibm.wala.ipa.slicer.Slicer;
import com.ibm.wala.ipa.slicer.Slicer.ControlDependenceOptions;
import com.ibm.wala.ipa.slicer.Slicer.DataDependenceOptions;
import com.ibm.wala.ipa.slicer.Statement;
import com.ibm.wala.ipa.slicer.Statement.Kind;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.config.AnalysisScopeReader;
import com.ibm.wala.core.tests.slicer.SlicerTest;
import com.ibm.wala.util.io.FileProvider;

public class SimpleSlicer {

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
                System.out.println("Unspecified analysis");
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
    public static void slice(
            String appJar,
            String srcCaller,
            String srcCallee,
            String analysis) {
        try {
            final long startTime = System.currentTimeMillis();
            File exclusions = (new FileProvider()).getFile(CallGraphTestUtil.REGRESSION_EXCLUSIONS);
            AnalysisScope scope = AnalysisScopeReader.makeJavaBinaryAnalysisScope(appJar, exclusions);
            ClassHierarchy cha = ClassHierarchy.make(scope);

            Iterable<Entrypoint> entrypoints = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(scope, cha);
            AnalysisOptions options = new AnalysisOptions(scope, entrypoints);

            // build the call graph
            CallGraphBuilder builder = makeCallGraphBuilder(analysis, options, new AnalysisCache(), cha, scope);
            CallGraph cg = builder.makeCallGraph(options, null);

            DataDependenceOptions dataOptions = DataDependenceOptions.FULL;
            ControlDependenceOptions controlOptions = ControlDependenceOptions.FULL;

            CGNode callerNode = SlicerTest.findMethod(cg, srcCaller);
            Statement stmt = SlicerTest.findCallTo(callerNode, srcCallee);
            System.err.println("Statement: " + stmt);

            stmt = getReturnStatementForCall(stmt);
            Collection<Statement> slice = null;

            slice = Slicer.computeForwardSlice(stmt, cg, builder.getPointerAnalysis(), dataOptions, controlOptions);

            final long endTime = System.currentTimeMillis();
            dumpSlice(slice);

            report(appJar, srcCaller, srcCallee, analysis, endTime - startTime);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

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
     * Print out statements to stdout
     * @param slice
     */
    public static void dumpSlice(Collection<Statement> slice) {
        for (Statement s : slice) {
            System.out.println(s);
        }
    }

    /**
     * Provide csv separated reporting of execution time
     * @param app
     * @param caller
     * @param callee
     * @param analysis
     * @param ms
     */
    public static void report(String app, String caller, String callee, String analysis, long ms) {
        System.out.println(app + "," + caller + "," + callee + "," + analysis + "," + ms);
    }

    /**
     * Help message
     */
    public static void help() {
        System.out.println(
                "Usage:java -jar slicer.java slicing.Slicer <target-jar-path> <caller-sig> <callee-sig>\n" +
                "Method signatures should correspond to that found in the bytecode (javac -s)"
        );
    }

    /**
     * Run experiment on a given jar + caller + callee
     * @param args
     */
    public static void main(String[] args) {
        if (args.length != 3) {
            help();
            System.exit(1);
        }

        String jarPath = args[0];
        String srcCaller = args[1];
        String srcCallee = args[2];
        String[] analysisTypes = new String[]{"0cfa", "vanilla-1cfa", "container-1cfa"};

        for (String analysis : analysisTypes) {
            slice(jarPath, srcCaller, srcCallee, analysis);
        }
    }
}