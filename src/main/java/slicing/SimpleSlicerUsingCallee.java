/*
Sourced from http://wala.sourceforge.net/wiki/index.php/UserGuide:Slicer
and slightly modified

Original sources can be found at
https://github.com/SCanDroid/WALA/blob/master/com.ibm.wala.core.tests/src/com/ibm/wala/examples/drivers/PDFSlice.java
https://github.com/wala/WALA/blob/master/com.ibm.wala.core.tests/src/com/ibm/wala/examples/drivers/PDFWalaIR.java
*/
package slicing;

import com.ibm.wala.ipa.callgraph.*;
import com.ibm.wala.ipa.callgraph.impl.Util;
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

import java.io.*;
import java.util.*;

public class SimpleSlicerUsingCallee {

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
     * @param srcCallee
     * @param analysis
     */
    public static void slice(String appJar, String srcCallee, String analysis) {
        try {
            // naive timing, but fine for example purposes
            final long startTime = System.currentTimeMillis();

            AnalysisScope scope = AnalysisScopeReader.makeJavaBinaryAnalysisScope(appJar, EXCLUSIONS);

            // create class hierarchy, wala needs to know the lay of the land
            ClassHierarchy cha = ClassHierarchy.make(scope);
            Iterable<Entrypoint> entrypoints = Util.makeMainEntrypoints(scope, cha);
            AnalysisOptions options = new AnalysisOptions(scope, entrypoints);


            // create method reference for callee
            MethodReference srcCalleeRef = StringStuff.makeMethodReference(srcCallee);

            // build the call graph for entire jar (in reality this would likely be done just for the class)
            CallGraphBuilder builder = makeCallGraphBuilder(analysis, options, new AnalysisCache(), cha, scope);
            CallGraph cg = builder.makeCallGraph(options, null);
            // pointer analysis
            PointerAnalysis pa = builder.getPointerAnalysis();


            // data and control flow dependencies (for reachability) in slicing
            DataDependenceOptions dataOptions = DataDependenceOptions.FULL;
            ControlDependenceOptions controlOptions = ControlDependenceOptions.FULL;

            // find all callers that call srcCallexe
            List<CGNode> callerNodes = findCallers(cg, srcCalleeRef);

            // find all call sites
            List<Statement> calls = new ArrayList<Statement>();
            for (CGNode caller : callerNodes) {
                calls.addAll(findCallsTo(caller, srcCalleeRef));
            }

            // find all return statements for calls
            List<Statement> returns = new ArrayList<>();
            for (Statement call : calls) {
                returns.add(getReturnStatementForCall(call));
            }

            System.out.println("Collected " + returns.size() + " return sites to use as criteria for slicing");

            // collect forwards
            List<Statement> slices = new ArrayList<>();
            for(Statement ret : returns) {
                System.out.println("===> Computing slice");
                Collection<Statement> slice = Slicer.computeForwardSlice(ret, cg, pa, dataOptions, controlOptions);
                slices.addAll(slice);
                System.out.println("===> Done with slice");
            }

            // note that the two print statements above are factoring into this time
            final long endTime = System.currentTimeMillis();
            System.out.println("Collected " + slices.size() + " statements in slices");
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
                    SimpleSlicerUsingCallee.class.getClassLoader().getResourceAsStream("exclusions.txt");
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


    // find methods that perform a call to method mr in the callgraph cg
    public static List<CGNode> findCallers(CallGraph cg, MethodReference mr) {
        List<CGNode> callers = new ArrayList<>();
        for (Iterator<CGNode> it = cg.iterator(); it.hasNext();) {
            CGNode callee = it.next();
            if (callee.getMethod().getReference().equals(mr)) {
                for(Iterator<CGNode> callerIt = cg.getPredNodes(callee); callerIt.hasNext(); ) {
                    callers.add(callerIt.next());
                }
                return callers;
            }
        }

        Assertions.UNREACHABLE("failed to find callers for " + mr.toString());
        return callers;
    }


    // find all call sites targeting method in a given call graph node
    public static List<Statement> findCallsTo(CGNode n, MethodReference method) {
        List<Statement> statements = new ArrayList<>();
        IR ir = n.getIR();
        for (Iterator<SSAInstruction> it = ir.iterateAllInstructions(); it.hasNext();) {
            SSAInstruction s = it.next();
            if (s instanceof SSAInvokeInstruction) {
                SSAInvokeInstruction call = (SSAInvokeInstruction) s;
                if (call.getCallSite().getDeclaredTarget().equals(method)) {
                    IntSet indices = ir.getCallInstructionIndices(((SSAInvokeInstruction) s).getCallSite());
                    Assertions.productionAssertion(indices.size() == 1, "expected 1 but got " + indices.size());
                    Statement callStmt = new NormalStatement(n, indices.intIterator().next());
                    statements.add(callStmt);
                }
            }
        }
        if (statements.size() == 0) {
            Assertions.UNREACHABLE("failed to find call to " + method.toString() + " in " + n);

        }
        return statements;
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
                "Usage:java -jar slicer.java slicing.Slicer <target-jar-path> <callee-sig> <analysis>\n" +
                "Method signatures should correspond to that found in the bytecode (javap -s)\n" +
                "Analysis must be one of: 0cfa, vanilla-1cfa, container-1cfa\n" +
                "For example:\n" +
                "slicing.SimpleSlicerUsingCallee example.jar 'Example.bye(Ljava/lang/String;)Ljava/lang/String;' 0cfa\n"
        );
    }

    /**
     * Run experiment on a given jar + caller + callee + analysis
     * @param args
     */
    public static void main(String[] args) {
        List<String> analysisNames = Arrays.asList("0cfa", "vanilla-1cfa", "container-1cfa");
        if (args.length != 3) {
            help();
            System.exit(1);
        }

        String jarPath = args[0];
        String srcCallee = args[1];
        String analysis = args[2];

        if (!analysisNames.contains(analysis)) {
            help();
            System.exit(1);
        }

        slice(jarPath, srcCallee, analysis);
    }
}