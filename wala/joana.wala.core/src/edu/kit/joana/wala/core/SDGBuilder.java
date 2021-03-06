/**
 * This file is part of the Joana IFC project. It is developed at the
 * Programming Paradigms Group of the Karlsruhe Institute of Technology.
 *
 * For further details on licensing please read the information at
 * http://joana.ipd.kit.edu or contact the authors.
 */
package edu.kit.joana.wala.core;

import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.jgrapht.DirectedGraph;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.cfg.exc.ExceptionPruningAnalysis;
import com.ibm.wala.cfg.exc.InterprocAnalysisResult;
import com.ibm.wala.cfg.exc.NullPointerAnalysis;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.escape.TrivialMethodEscape;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisOptions.ReflectionOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraphBuilder;
import com.ibm.wala.ipa.callgraph.CallGraphBuilderCancelException;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.impl.SubtypesEntrypoint;
import com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis;
import com.ibm.wala.ipa.callgraph.pruned.CallGraphPruning;
import com.ibm.wala.ipa.callgraph.pruned.PrunedCallGraph;
import com.ibm.wala.ipa.cfg.ExceptionPrunedCFG;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.analysis.ExplodedControlFlowGraph;
import com.ibm.wala.ssa.analysis.IExplodedBasicBlock;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.MonitorUtil;
import com.ibm.wala.util.MonitorUtil.IProgressMonitor;
import com.ibm.wala.util.WalaException;
import com.ibm.wala.util.collections.Pair;
import com.ibm.wala.util.graph.Graph;
import com.ibm.wala.util.graph.GraphIntegrity.UnsoundGraphException;
import com.ibm.wala.util.graph.impl.SparseNumberedGraph;

import edu.kit.joana.ifc.sdg.graph.SDG;
import edu.kit.joana.ifc.sdg.util.SDGConstants;
import edu.kit.joana.util.Log;
import edu.kit.joana.util.LogUtil;
import edu.kit.joana.util.Logger;
import edu.kit.joana.wala.core.CallGraph.CallGraphFilter;
import edu.kit.joana.wala.core.CallGraph.Edge;
import edu.kit.joana.wala.core.CallGraph.Node;
import edu.kit.joana.wala.core.accesspath.AccessPath;
import edu.kit.joana.wala.core.clinit.StaticInitializers;
import edu.kit.joana.wala.core.interference.Call2ForkConverter;
import edu.kit.joana.wala.core.interference.InterferenceComputation;
import edu.kit.joana.wala.core.interference.InterferenceEdge;
import edu.kit.joana.wala.core.interference.ThreadInformationProvider;
import edu.kit.joana.wala.core.joana.JoanaConverter;
import edu.kit.joana.wala.core.killdef.IFieldsMayMod;
import edu.kit.joana.wala.core.killdef.LocalKillingDefs;
import edu.kit.joana.wala.core.killdef.impl.FieldsMayModComputation;
import edu.kit.joana.wala.core.killdef.impl.SimpleFieldsMayMod;
import edu.kit.joana.wala.core.params.FlatHeapParams;
import edu.kit.joana.wala.core.params.StaticFieldParams;
import edu.kit.joana.wala.core.params.objgraph.ModRefCandidates;
import edu.kit.joana.wala.core.params.objgraph.ObjGraphParams;
import edu.kit.joana.wala.core.pointsto.WalaPointsToUtil;
import edu.kit.joana.wala.flowless.util.Util;
import edu.kit.joana.wala.flowless.wala.ObjSensContextSelector;
import edu.kit.joana.wala.summary.SummaryComputation;
import edu.kit.joana.wala.summary.WorkPackage;
import edu.kit.joana.wala.summary.WorkPackage.EntryPoint;
import edu.kit.joana.wala.util.EdgeFilter;
import edu.kit.joana.wala.util.WriteGraphToDot;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;

public class SDGBuilder implements CallGraphFilter {

	private static final Logger debug = Log.getLogger(Log.L_WALA_CORE_DEBUG);
	private static final boolean IS_DEBUG = debug.isEnabled();
	
	public static final int PDG_FAKEROOT_ID = 0;
	public static final int PDG_THREAD_START_ID = 1;
	public static final int PDG_THREAD_RUN_ID = 2;
	// start at 2+1 == 3 because other ids are reserved.
	public static final int PDG_START_ID = 3;
	public static final int NO_PDG_ID = -1;
	public static boolean DATA_FLOW_FOR_GET_FROM_FIELD_NODE = false;
	// private static Context DEFAULT_CONTEXT = Everywhere.EVERYWHERE;
	// private static SSAOptions DEFAULT_SSA_OPTIONS = new SSAOptions();

	public final Config cfg;

	public static enum ExceptionAnalysis {
		IGNORE_ALL,
		ALL_NO_ANALYSIS,
		INTERPROC,
		INTRAPROC
	}

	public static enum PointsToPrecision {
		TYPE,
		CONTEXT_SENSITIVE,
		OBJECT_SENSITIVE
	}

	public static enum StaticInitializationTreatment {
		NONE,
		SIMPLE,
		ACCURATE
	}

	public static enum FieldPropagation {
		FLAT,
		OBJ_GRAPH,
		OBJ_GRAPH_NO_FIELD_MERGE,
		OBJ_GRAPH_FIXPOINT_PROPAGATION,
		OBJ_GRAPH_NO_ESCAPE,
		OBJ_GRAPH_NO_OPTIMIZATION,
		OBJ_GRAPH_SIMPLE_PROPAGATION,
		OBJ_TREE,
		OBJ_TREE_NO_FIELD_MERGE
	}

	public static SDGBuilder create(final Config cfg) throws UnsoundGraphException, CancelException {
		IProgressMonitor progress = NullProgressMonitor.INSTANCE;

		SDGBuilder builder = new SDGBuilder(cfg);
		builder.run(progress);

		return builder;
	}

	public static SDGBuilder create(final Config cfg, final com.ibm.wala.ipa.callgraph.CallGraph walaCG,
			final PointerAnalysis pts) throws UnsoundGraphException, CancelException {
		IProgressMonitor progress = NullProgressMonitor.INSTANCE;

		SDGBuilder builder = new SDGBuilder(cfg);
		builder.run(walaCG, pts, progress);

		return builder;
	}

	public static SDG build(final Config cfg, IProgressMonitor progress) throws UnsoundGraphException, CancelException {
		SDG sdg = null;
		WorkPackage pack = null;

		/* additional scope so SDGBuilder object can be garbage collected */{
			SDGBuilder builder = new SDGBuilder(cfg);
			builder.run(progress);
			sdg = convertToJoana(cfg.out, builder, progress);

			if (cfg.computeSummary) {
				pack = createSummaryWorkPackage(cfg.out, builder, sdg, progress);
			}
		}

		if (cfg.computeSummary) {
			if (cfg.accessPath) {
				computeDataAndAliasSummaryEdges(cfg.out, pack, sdg, progress);
			} else {
				computeSummaryEdges(cfg.out, pack, sdg, progress);
			}
		}

		return sdg;
	}

	public static Pair<SDG, SDGBuilder> buildAndKeepBuilder(final Config cfg, IProgressMonitor progress)
			throws UnsoundGraphException, CancelException {
		SDG sdg = null;
		WorkPackage pack = null;

		SDGBuilder builder = new SDGBuilder(cfg);
		builder.run(progress);
		sdg = convertToJoana(cfg.out, builder, progress);

		if (cfg.computeSummary) {
			pack = createSummaryWorkPackage(cfg.out, builder, sdg, progress);
		}

		if (cfg.computeSummary) {
			if (cfg.accessPath) {
				computeDataAndAliasSummaryEdges(cfg.out, pack, sdg, progress);
			} else {
				computeSummaryEdges(cfg.out, pack, sdg, progress);
			}
		}

		return Pair.make(sdg, builder);
	}

	public static SDG build(final Config cfg) throws UnsoundGraphException, CancelException {
		IProgressMonitor progress = NullProgressMonitor.INSTANCE;
		return build(cfg, progress);
	}

	public static SDG convertToJoana(PrintStream out, SDGBuilder builder, IProgressMonitor progress)
			throws CancelException {
		out.print("convert");
		final SDG sdg = JoanaConverter.convert(builder, progress);
		out.print(".");

		return sdg;
	}

	private static WorkPackage createSummaryWorkPackage(PrintStream out, SDGBuilder builder, SDG sdg,
			IProgressMonitor progress) {
		out.print("summary");
		Set<EntryPoint> entries = new TreeSet<EntryPoint>();
		PDG pdg = builder.getMainPDG();
		TIntSet formIns = new TIntHashSet();
		for (PDGNode p : pdg.params) {
			formIns.add(p.getId());
		}
		TIntSet formOuts = new TIntHashSet();
		formOuts.add(pdg.exception.getId());
		formOuts.add(pdg.exit.getId());
		EntryPoint ep = new EntryPoint(pdg.entry.getId(), formIns, formOuts);
		entries.add(ep);
		WorkPackage pack = WorkPackage.create(sdg, entries, sdg.getName());
		out.print(".");

		return pack;
	}

	private static void computeSummaryEdges(PrintStream out, WorkPackage pack, SDG sdg, IProgressMonitor progress)
			throws CancelException {
		SummaryComputation.compute(pack, progress);
		out.print(".");
	}

	private static void computeDataAndAliasSummaryEdges(PrintStream out, WorkPackage pack, SDG sdg,
			IProgressMonitor progress) throws CancelException {
		SummaryComputation.computeNoAliasDataDep(pack, progress);
		out.print(".");
		SummaryComputation.computeFullAliasDataDep(pack, progress);
		out.print(".");
	}

	private final ParameterFieldFactory params = new ParameterFieldFactory();
	private int currentNodeId = 1;
	private int pdgId = getMainId();
	private final List<PDG> pdgs = new LinkedList<PDG>();
	/**
	 * currently unused - could later be used to append static initializer calls
	 * to it
	 */
	// private final DependenceGraph startPDG = new DependenceGraph();

	private CallGraph cg = null;
	private com.ibm.wala.ipa.callgraph.CallGraph nonPrunedCG = null;
	private Map<PDGNode, TIntSet> call2alloc = null;
	private InterprocAnalysisResult<SSAInstruction, IExplodedBasicBlock> interprocExceptionResult = null;

	private SDGBuilder(final Config cfg) {
		this.cfg = cfg;
	}

	private void run(final IProgressMonitor progress) throws UnsoundGraphException, CancelException {
		if (debug.isEnabled()) {
			debug.outln("Running sdg computation with configuration:");
			debug.outln(LogUtil.attributesToString(cfg));
		}
		cfg.out.print("\n\tcallgraph: ");
		progress.subTask("building call graph...");
		final CGResult walaCG = buildCallgraph(progress);
		progress.worked(1);
		run(walaCG, progress);
	}

	private void run(final com.ibm.wala.ipa.callgraph.CallGraph walaCG, final PointerAnalysis pts,
			final IProgressMonitor progress) throws UnsoundGraphException, CancelException {
		cfg.out.print("\n\tcallgraph: ");

		final CGResult cgresult = new CGResult(walaCG, pts);

		run(cgresult, progress);
	}

	private void run(final CGResult initalCG, final IProgressMonitor progress) throws UnsoundGraphException,
			CancelException {
		nonPrunedCG = initalCG.cg;
		progress.subTask("pruning call graph...");
		cg = convertAndPruneCallGraph(cfg.prunecg, initalCG, progress);
		progress.worked(1);
		if (cfg.debugCallGraphDotOutput) {
			debugDumpGraph(cg, "callgraph.dot");
		}

		cfg.out.println(cg.vertexSet().size() + " nodes and " + cg.edgeSet().size() + " edges");

		if (cfg.exceptions == ExceptionAnalysis.INTERPROC) {
			cfg.out.print("\tinterproc exception analysis... ");

			try {
				interprocExceptionResult = NullPointerAnalysis.computeInterprocAnalysis(nonPrunedCG, progress);
			} catch (WalaException e) {
				throw new CancelException(e);
			}

			cfg.out.println("done.");
			if (IS_DEBUG) debug.outln(interprocExceptionResult.toString());
		}

		pdgId = getMainId();
		{
			// create main pdg
			final CGNode cgm = cg.getRoot().node;
			final PDG pdg = createAndAddPDG(cgm, progress);
			MonitorUtil.throwExceptionIfCanceled(progress);

			if (cfg.debugManyGraphsDotOutput) {
				debugOutput(pdg);
			}
		}

		cfg.out.print("\tintraproc: ");
		progress.subTask("computing intraprocedural flow...");
		final int fivePercent = cg.vertexSet().size() / 20;
		int currentNum = 1;

		for (CallGraph.Node node : cg.vertexSet()) {
			if (node.node.getMethod() == cfg.entry) {
				continue;
			}

			final CGNode cgm = node.node;
			final PDG pdg = createAndAddPDG(cgm, progress);

			currentNum++;
			if (fivePercent > 0) {
				if (currentNum % fivePercent == 0) {
					int percent = currentNum / fivePercent;
					String str = ".";
					if (percent == 5) {
						str = "25%";
					} else if (percent == 10) {
						str = "50%";
					} else if (percent == 15) {
						str = "75%";
					} else if (percent == 20) {
						str = "100%";
					}

					cfg.out.print(str);
				}
			} else {
				cfg.out.print(".");
			}

			MonitorUtil.throwExceptionIfCanceled(progress);

			if (cfg.debugManyGraphsDotOutput) {
				debugOutput(pdg);
			}
		}
		progress.worked(1);

		if (cfg.exceptions == ExceptionAnalysis.INTERPROC) {
			// save memory - let garbage collector do its work.
			interprocExceptionResult = null;
		}

		cfg.out.print("\n\tinterproc: ");
		progress.subTask("computing interprocedural flow...");
		cfg.out.print("calls");
		// connect call sites
		for (PDG pdg : pdgs) {
			if (isImmutableStub(pdg.getMethod().getDeclaringClass().getReference())) {
				continue;
			}

			for (PDGNode call : pdg.getCalls()) {
				Set<PDG> tgts = findPossibleTargets(cg, pdg, call);
				pdg.connectCall(call, tgts);
			}
		}
		cfg.out.print(".");
		progress.worked(1);
		if (cfg.staticInitializers != StaticInitializationTreatment.NONE) {
			progress.subTask("handling static initializers...");
			cfg.out.print("clinit");
			switch (cfg.staticInitializers) {
			case SIMPLE:
				// nothing to do, this is handled though fakeWorldClinit of wala
				// callgraph
				break;
			case ACCURATE:
				StaticInitializers.compute(this, progress);
				break;
			default:
				throw new IllegalStateException("Unknown option: " + cfg.staticInitializers);
			}
			cfg.out.print(".");

		}
		progress.worked(1);
		cfg.out.print("statics");
		// propagate static root nodes and add dataflow
		progress.subTask("adding data flow for static fields...");
		addDataFlowForStaticFields(progress);
		progress.worked(1);
		cfg.out.print(".");

		cfg.out.print("heap");
		// compute dataflow through heap/fields (no-alias)
		progress.subTask("adding data flow for heap fields...");
		addDataFlowForHeapFields(progress);
		progress.worked(1);
		cfg.out.print(".");

		cfg.out.print("misc");
		// compute dummy connections for unresolved calls
		progress.subTask("adding dummy data flow to unresolved calls...");
		addDummyDataFlowToUnresolvedCalls();
		progress.worked(1);
		cfg.out.print(".");

		if (cfg.localKillingDefs) {
			cfg.out.print("killdef");
			progress.subTask("computing local killing defintions...");
			LocalKillingDefs.run(this, progress);
			cfg.out.print(".");
		}

		if (cfg.accessPath) {
			cfg.out.print("accesspath");
			progress.subTask("computing access path information...");
			// compute access path info
			AccessPath.compute(this, getMainPDG());
			cfg.out.print(".");
		}

		addReturnEdges();
		progress.worked(1);

		if (cfg.computeInterference) {
			cfg.out.print("interference");
			ThreadInformationProvider tiProvider = new ThreadInformationProvider(this);
			call2alloc = tiProvider.getAllocationSitesForThreadStartCalls();
			progress.subTask("adding interference edges...");
			addInterferenceEdges(tiProvider, progress);
			progress.subTask("introducing fork edges...");
			introduceForkEdges(tiProvider);

			cfg.out.print(".");
		}

		progress.worked(1);
	}

	public ControlFlowGraph<SSAInstruction, IExplodedBasicBlock> createExceptionAnalyzedCFG(final CGNode n,
			final IProgressMonitor progress) throws UnsoundGraphException, CancelException {
		ControlFlowGraph<SSAInstruction, IExplodedBasicBlock> ecfg = null;

		switch (cfg.exceptions) {
		case ALL_NO_ANALYSIS: {
			ecfg = ExplodedControlFlowGraph.make(n.getIR());
		}
			break;
		case INTRAPROC: {
			final ExceptionPruningAnalysis<SSAInstruction, IExplodedBasicBlock> npa = NullPointerAnalysis
					.createIntraproceduralExplodedCFGAnalysis(n.getIR());

			npa.compute(progress);

			ecfg = npa.getCFG();
		}
			break;
		case INTERPROC: {
			if (interprocExceptionResult == null) {
				throw new IllegalStateException("called at the wrong time. we do not keep the interprocedural analysis"
						+ " result during the whole computation due to memory usage.");
			}

			ExceptionPruningAnalysis<SSAInstruction, IExplodedBasicBlock> npa = interprocExceptionResult.getResult(n);

			if (npa == null) {
				npa = NullPointerAnalysis.createIntraproceduralExplodedCFGAnalysis(n.getIR());
			}

			npa.compute(progress);
			// removed++;
			// ExceptionPruningAnalysis<SSAInstruction, IExplodedBasicBlock>
			// npa2 =
			// NullPointerAnalysis.createIntraproceduralExplodedCFGAnalysis(n.getIR());
			// int removed2 = npa2.compute(progress);
			// removed2++;

			ecfg = npa.getCFG();
		}
			break;
		case IGNORE_ALL: {
			ecfg = ExceptionPrunedCFG.make(ExplodedControlFlowGraph.make(n.getIR()));
		}
			break;
		}

		return ecfg;
	}

	private void addReturnEdges() {
		for (PDG pdg : getAllPDGs()) {
			for (PDGNode call : pdg.getCalls()) {
				for (PDG tgt : getPossibleTargets(call)) {
					// add return edge from callee to unique successor of call
					// node
					// if successor is not unique, add dummy node
					PDGNode exitOfCallee = tgt.exit;
					List<PDGNode> callSucc = new LinkedList<PDGNode>();
					for (PDGEdge callOut : pdg.outgoingEdgesOf(call)) {
						if (call.equals(callOut.from) && callOut.kind == PDGEdge.Kind.CONTROL_FLOW) {
							callSucc.add(callOut.to);
						}
					}

					PDGNode uniqueSucc;

					if (callSucc.size() == 1 && callSucc.get(0).getLabel().equals(SDGConstants.CALLRET_LABEL)) {
						// already added a dummy node
						uniqueSucc = callSucc.get(0);

					} else {
						uniqueSucc = pdg.createCallReturnNode(call);

						// unique successor inherits all outgoing control flow
						// edges of the call node
						for (PDGNode succOfCall : callSucc) {
							pdg.removeEdge(call, succOfCall, PDGEdge.Kind.CONTROL_FLOW);
							pdg.addEdge(uniqueSucc, succOfCall, PDGEdge.Kind.CONTROL_FLOW);
						}

						// As a potential occurring exception controls iff the call exists normally,
						// we add a control dependence to the call return node.
						// This is a more natural and precise solution to the bug reported by benedikt (bug #16)
						// We also move all control dependencies from call node to call return
						final PDGNode excRet = pdg.getExceptionOut(call);
						pdg.addEdge(excRet, uniqueSucc, PDGEdge.Kind.CONTROL_DEP_EXPR);

						final List<PDGEdge> callControlDeps = new LinkedList<PDGEdge>();
						for (final PDGEdge e : pdg.outgoingEdgesOf(call)) {
							if (e.kind == PDGEdge.Kind.CONTROL_DEP) {
								callControlDeps.add(e);
								pdg.addEdge(uniqueSucc, e.to, PDGEdge.Kind.CONTROL_DEP);
							}
						}
						pdg.removeAllEdges(callControlDeps);

						// add edge from call node to unique successor
						pdg.addEdge(call, uniqueSucc, PDGEdge.Kind.CONTROL_FLOW);
						pdg.addEdge(call, uniqueSucc, PDGEdge.Kind.CONTROL_DEP_EXPR);
					}

					// add return edge
					tgt.addVertex(uniqueSucc);
					tgt.addEdge(exitOfCallee, uniqueSucc, PDGEdge.Kind.RETURN);
					if (IS_DEBUG) debug.outln("Added return edge between " + exitOfCallee + " and " + uniqueSucc + ".");
				}
			}
		}
	}

	private void addInterferenceEdges(ThreadInformationProvider tiProvider, IProgressMonitor progress)
			throws CancelException {
		if (tiProvider.hasThreadStartMethod()) {
			EscapeAnalysis escapeAnalysis = new MethodEscapeAnalysis(new TrivialMethodEscape(
					getNonPrunedWalaCallGraph(), getPointerAnalysis().getHeapGraph()));
			Set<InterferenceEdge> interferences = InterferenceComputation.computeInterference(this, tiProvider, true,
					false, escapeAnalysis, progress);
			assert interferences != null;
			for (InterferenceEdge iEdge : interferences) {
				iEdge.addToPDG();
			}
		}
	}

	private void introduceForkEdges(ThreadInformationProvider tiProvider) {
		new Call2ForkConverter(this, tiProvider).run();
	}

	public static final int DO_NOT_PRUNE = -1;

	private static class CGResult {
		private final com.ibm.wala.ipa.callgraph.CallGraph cg;
		private final PointerAnalysis pts;

		private CGResult(com.ibm.wala.ipa.callgraph.CallGraph cg, PointerAnalysis pts) {
			this.cg = cg;
			this.pts = pts;
		}
	}

	private CGResult buildCallgraph(final IProgressMonitor progress) throws IllegalArgumentException,
			CallGraphBuilderCancelException {
		List<Entrypoint> entries = new LinkedList<Entrypoint>();
		Entrypoint ep = new SubtypesEntrypoint(cfg.entry, cfg.cha);
		entries.add(ep);
		AnalysisOptions options = new AnalysisOptions(cfg.scope, entries);
		if (cfg.ext.resolveReflection()) {
			options.setReflectionOptions(ReflectionOptions.NO_STRING_CONSTANTS);
		}

		CallGraphBuilder cgb = null;
		switch (cfg.pts) {
		case TYPE:
			cgb = WalaPointsToUtil.makeContextFreeType(options, cfg.cache, cfg.cha, cfg.scope);
			break;
		case CONTEXT_SENSITIVE:
			cgb = WalaPointsToUtil.makeContextSensSite(options, cfg.cache, cfg.cha, cfg.scope);
			break;
		case OBJECT_SENSITIVE:
			cgb = WalaPointsToUtil.makeObjectSens(options, cfg.cache, cfg.cha, cfg.scope, cfg.objSensFilter);
			break;
		}

		com.ibm.wala.ipa.callgraph.CallGraph callgraph = cgb.makeCallGraph(options, progress);

		return new CGResult(callgraph, cgb.getPointerAnalysis());
	}

	private CallGraph convertAndPruneCallGraph(final int prune, final CGResult walaCG, final IProgressMonitor progress)
			throws IllegalArgumentException, CallGraphBuilderCancelException {

		com.ibm.wala.ipa.callgraph.CallGraph curcg = walaCG.cg;

		if (prune >= 0) {
			CallGraphPruning cgp = new CallGraphPruning(walaCG.cg);
			Set<CGNode> appl = cgp.findApplicationNodes(prune);
			PrunedCallGraph pcg = new PrunedCallGraph(walaCG.cg, appl);
			curcg = pcg;
		}

		progress.worked(1);

		final CallGraph cg = CallGraph.build(this, curcg, walaCG.pts, cfg.entry, progress);

		return cg;
	}

	private void addDataFlowForHeapFields(IProgressMonitor progress) throws CancelException {
		switch (cfg.fieldPropagation) {
		case FLAT: {
			FlatHeapParams.compute(this, progress);
		}
			break;
		case OBJ_GRAPH: {
			final ObjGraphParams.Options opt = new ObjGraphParams.Options();
			ObjGraphParams.compute(this, opt, progress);
		}
			break;
		case OBJ_GRAPH_FIXPOINT_PROPAGATION: {
			final ObjGraphParams.Options opt = new ObjGraphParams.Options();
			opt.isUseAdvancedInterprocPropagation = true;
			ObjGraphParams.compute(this, opt, progress);
		}
			break;
		case OBJ_GRAPH_NO_ESCAPE: {
			final ObjGraphParams.Options opt = new ObjGraphParams.Options();
			opt.isCutOffUnreachable = false;
			opt.isUseAdvancedInterprocPropagation = false;
			ObjGraphParams.compute(this, opt, progress);
		}
			break;
		case OBJ_GRAPH_NO_FIELD_MERGE: {
			final ObjGraphParams.Options opt = new ObjGraphParams.Options();
			opt.isMergeOneFieldPerParent = false;
			opt.isUseAdvancedInterprocPropagation = false;
			opt.maxNodesPerInterface = ObjGraphParams.Options.UNLIMITED_NODES_PER_INTERFACE;
			ObjGraphParams.compute(this, opt, progress);
		}
			break;
		case OBJ_GRAPH_SIMPLE_PROPAGATION: {
			final ObjGraphParams.Options opt = new ObjGraphParams.Options();
			opt.isUseAdvancedInterprocPropagation = false;
			ObjGraphParams.compute(this, opt, progress);
		}
			break;
		case OBJ_GRAPH_NO_OPTIMIZATION: {
			final ObjGraphParams.Options opt = new ObjGraphParams.Options();
			opt.isCutOffImmutables = false;
			opt.isCutOffUnreachable = false;
			opt.isMergeException = false;
			opt.isMergeOneFieldPerParent = false;
			opt.isMergePrunedCallNodes = false;
			opt.maxNodesPerInterface = ObjGraphParams.Options.UNLIMITED_NODES_PER_INTERFACE;
			opt.isUseAdvancedInterprocPropagation = false;
			ObjGraphParams.compute(this, opt, progress);
		}
			break;
		case OBJ_TREE: {
			final ObjGraphParams.Options opt = new ObjGraphParams.Options();
			opt.isCutOffImmutables = true;
			opt.isCutOffUnreachable = true;
			opt.isMergeException = false;
			opt.isMergeOneFieldPerParent = true;
			opt.isMergePrunedCallNodes = true;
			opt.isUseAdvancedInterprocPropagation = true;
			opt.convertToObjTree = true;
			ObjGraphParams.compute(this, opt, progress);
		}
			break;
		case OBJ_TREE_NO_FIELD_MERGE: {
			final ObjGraphParams.Options opt = new ObjGraphParams.Options();
			opt.isCutOffImmutables = true;
			opt.isCutOffUnreachable = true;
			opt.isMergeException = false;
			opt.isMergeOneFieldPerParent = false;
			opt.isMergePrunedCallNodes = false;
			opt.isUseAdvancedInterprocPropagation = true;
			opt.convertToObjTree = true;
			ObjGraphParams.compute(this, opt, progress);
		}
			break;
		default:
			throw new IllegalStateException("Unknown field propagation option: " + cfg.fieldPropagation);
		}
	}

	private void addDataFlowForStaticFields(IProgressMonitor progress) throws CancelException {
		StaticFieldParams.compute(this, progress);
	}

	private void addDummyDataFlowToUnresolvedCalls() {
		// connect call sites
		for (PDG pdg : pdgs) {
			if (isImmutableStub(pdg.getMethod().getDeclaringClass().getReference())) {
				// direct data deps from all formal-in to formal-outs
				List<PDGNode> inParam = new LinkedList<PDGNode>();
				List<PDGNode> outParam = new LinkedList<PDGNode>();
				for (PDGEdge e : pdg.outgoingEdgesOf(pdg.entry)) {
					if (e.kind == PDGEdge.Kind.CONTROL_DEP_EXPR) {
						switch (e.to.getKind()) {
						case FORMAL_IN:
							inParam.add(e.to);
							break;
						case EXIT:
							if (pdg.isVoid()) break;
						case FORMAL_OUT:
							outParam.add(e.to);
							break;
						}
					}
				}

				for (PDGNode ain : inParam) {
					for (PDGNode aout : outParam) {
						pdg.addEdge(ain, aout, PDGEdge.Kind.DATA_DEP);
					}
				}
			} else {
				for (PDGNode call : pdg.getCalls()) {
					Set<PDG> tgts = findPossibleTargets(cg, pdg, call);
					if (tgts.isEmpty() && !cfg.ext.isCallToModule((SSAInvokeInstruction) pdg.getInstruction(call))) {
						// do direct data deps dummies
						List<PDGNode> inParam = new LinkedList<PDGNode>();
						List<PDGNode> outParam = new LinkedList<PDGNode>();
						for (PDGEdge e : pdg.outgoingEdgesOf(call)) {
							if (e.kind == PDGEdge.Kind.CONTROL_DEP_EXPR) {
								switch (e.to.getKind()) {
								case ACTUAL_IN:
									inParam.add(e.to);
									break;
								case ACTUAL_OUT:
									outParam.add(e.to);
									break;
								}
							}
						}
	
						for (PDGNode ain : inParam) {
							for (PDGNode aout : outParam) {
								pdg.addEdge(ain, aout, PDGEdge.Kind.DATA_DEP);
							}
						}
					}
				}
			}
		}
	}

	public Set<PDG> findPossibleTargets(CallGraph cg, PDG caller, PDGNode call) {
		final Set<PDG> callees = new HashSet<PDG>();

		final Node cgCaller = cg.findNode(caller.cgNode);
		final SSAInstruction instr = caller.getInstruction(call);

		for (final Edge cl : cg.findTarges(cgCaller, instr.iindex)) {
			final CGNode called = cl.to.node;
			final PDG callee = getPDGforMethod(called);
			callees.add(callee);
		}

		return callees;
	}

	public Set<PDG> getPossibleTargets(PDGNode call) {
		if (call.getKind() != PDGNode.Kind.CALL) {
			throw new IllegalArgumentException("Not a call node: " + call);
		}

		Set<PDG> tgts = new HashSet<PDG>();

		PDG pdgCaller = getPDGforId(call.getPdgId());

		for (PDGEdge out : pdgCaller.outgoingEdgesOf(call)) {
			if (out.kind == PDGEdge.Kind.CALL_STATIC || out.kind == PDGEdge.Kind.CALL_VIRTUAL) {
				PDGNode entry = out.to;
				PDG target = getPDGforId(entry.getPdgId());
				tgts.add(target);
			}
		}

		return tgts;
	}

	public Set<PDG> getPossibleCallers(final PDG callee) {
		final Set<PDG> callers = new HashSet<PDG>();

		for (final PDG pdg : getAllPDGs()) {
			if (pdg != callee) {
				if (pdg.containsVertex(callee.entry)) {
					callers.add(pdg);
				}
			} else {
				boolean found = false;
				for (final PDGNode call : pdg.getCalls()) {
					if (found) {
						break;
					}

					for (final PDGEdge e : pdg.outgoingEdgesOf(call)) {
						if (found) {
							break;
						}

						if ((e.kind == PDGEdge.Kind.CALL_STATIC || e.kind == PDGEdge.Kind.CALL_VIRTUAL)
								&& e.to == pdg.entry) {
							found = true;
						}
					}
				}

				if (found) {
					callers.add(callee);
				}
			}
		}

		return callers;
	}

	public PDG getPDGforMethod(CGNode n) {
		for (PDG pdg : pdgs) {
			if (n.equals(pdg.cgNode)) {
				return pdg;
			}
		}

		return null;
	}

	public int getNextNodeId() {
		final int id = currentNodeId;
		currentNodeId++;
		return id;
	}

	public IClassHierarchy getClassHierarchy() {
		return cfg.cha;
	}

	public ParameterFieldFactory getParameterFieldFactory() {
		return params;
	}

	public IMethod getEntry() {
		return cfg.entry;
	}

	public List<PDG> getAllPDGs() {
		return Collections.unmodifiableList(pdgs);
	}

	public PDG getPDGforId(int id) {
		for (PDG pdg : pdgs) {
			if (pdg.getId() == id) {
				return pdg;
			}
		}

		return null;
	}

	/**
	 * Returns a mapping which maps the id of a pdg node to the index of the ssa
	 * instruction it represents.
	 *
	 * @return a mapping which maps the id of a pdg node to the index of the ssa
	 *         instruction it represents
	 */
	public TIntIntMap getPDGNode2IIndex() {
		TIntIntHashMap ret = new TIntIntHashMap();

		for (PDG pdg : getAllPDGs()) {
			for (PDGNode node : pdg.vertexSet()) {
				SSAInstruction i = pdg.getInstruction(node);
				if (i != null) {
					ret.put(node.getId(), i.iindex);
				}
			}
		}

		return ret;
	}

	/**
	 * Returns a mapping between the entry nodes of the various pdgs to the id
	 * of the corresponding call graph nodes.
	 *
	 * @return a mapping between the entry nodes of the various pdgs to the id
	 *         of the corresponding call graph nodes
	 */
	public TIntIntMap getEntryNode2CGNode() {
		TIntIntHashMap ret = new TIntIntHashMap();

		for (PDG pdg : getAllPDGs()) {
			PDGNode pdgEntry = pdg.entry;
			ret.put(pdgEntry.getId(), pdg.cgNode.getGraphNodeId());
		}

		return ret;
	}

	public int getStartId() {
		return PDG_START_ID;
	}

	public int getMainId() {
		return PDG_START_ID + 1;
	}

	public PDG getMainPDG() {
		return getPDGforId(getMainId());
	}

	public Graph<PDG> createCallGraph() {
		Graph<PDG> cgPDG = new SparseNumberedGraph<PDG>();
		for (PDG pdg : pdgs) {
			cgPDG.addNode(pdg);
		}

		for (PDG pdg : pdgs) {
			for (PDGNode call : pdg.getCalls()) {
				Set<PDG> tgts = getPossibleTargets(call);
				for (PDG target : tgts) {
					cgPDG.addEdge(pdg, target);
				}
			}
		}

		return cgPDG;
	}

	public boolean isImmutableNoOutParam(TypeReference t) {
		final String name = t.getName().toString();

		for (final String im : cfg.immutableNoOut) {
			if (im.equals(name)) {
				return true;
			}
		}

		return false;
	}

	public boolean isImmutableStub(TypeReference t) {
		final String name = t.getName().toString();

		for (final String im : cfg.immutableStubs) {
			if (im.equals(name)) {
				return true;
			}
		}

		return false;
	}

	public boolean isIgnoreStaticFields(TypeReference t) {
		final String name = t.getName().toString();

		for (final String im : cfg.ignoreStaticFields) {
			if (im.equals(name)) {
				return true;
			}
		}

		return false;
	}

	public boolean isKeepPhiNodes() {
		return cfg.keepPhiNodes;
	}

	public boolean isNoBasePointerDependency() {
		return cfg.noBasePointerDependency;
	}

	private static void debugOutput(PDG pdg) {
		IMethod im = pdg.getMethod();
		final String prefix = WriteGraphToDot.sanitizeFileName(im.getName().toString());
		try {
			WriteGraphToDot.write(pdg, prefix + ".ddg.dot", new EdgeFilter<PDGEdge>() {
				public boolean accept(PDGEdge edge) {
					return edge.kind == PDGEdge.Kind.DATA_DEP;
				}
			});
			WriteGraphToDot.write(pdg, prefix + ".cdg.dot", new EdgeFilter<PDGEdge>() {
				public boolean accept(PDGEdge edge) {
					return edge.kind == PDGEdge.Kind.CONTROL_DEP;
				}
			});
			WriteGraphToDot.write(pdg, prefix + ".cfg.dot", new EdgeFilter<PDGEdge>() {
				public boolean accept(PDGEdge edge) {
					return edge.kind == PDGEdge.Kind.CONTROL_FLOW || edge.kind == PDGEdge.Kind.CONTROL_FLOW_EXC;
				}
			});
			WriteGraphToDot.write(pdg, prefix + ".pdg.dot", new EdgeFilter<PDGEdge>() {
				public boolean accept(PDGEdge edge) {
					return edge.kind == PDGEdge.Kind.CONTROL_DEP || edge.kind == PDGEdge.Kind.DATA_DEP;
				}
			});
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
	}

	/**
	 * @see CallGraph.CallGraphFilter
	 */
	public boolean ignoreCallsTo(IMethod m) {
		if (m.getDeclaringClass().getReference() == TypeReference.JavaLangObject) {
			if (m.isInit()) {
				return true;
			}
		}

		return false;
	}

	/**
	 * @see CallGraph.CallGraphFilter
	 */
	public boolean ignoreCallsFrom(IMethod m) {
		final TypeReference tr = m.getDeclaringClass().getReference();

		return isImmutableStub(tr);
	}

	/**
	 * @see CallGraph.CallGraphFilter
	 */
	public boolean ignoreWalaFakeWorldClinit() {
		return cfg.staticInitializers != StaticInitializationTreatment.SIMPLE;
	}

	public PointerAnalysis getPointerAnalysis() {
		return cg.getPTS();
	}

	public com.ibm.wala.ipa.callgraph.CallGraph getWalaCallGraph() {
		return cg.getOrig();
	}

	public com.ibm.wala.ipa.callgraph.CallGraph getNonPrunedWalaCallGraph() {
		return nonPrunedCG;
	}

	private static <V, E> void debugDumpGraph(final DirectedGraph<V, E> g, final String fileName) {
		try {
			WriteGraphToDot.write(g, fileName);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Configuration of the SDG computation.
	 *
	 * @author Juergen Graf <juergen.graf@gmail.com>
	 *
	 */
	public static class Config {
		public PrintStream out = System.out;
		public AnalysisScope scope = null;
		public AnalysisCache cache = null;
		public IClassHierarchy cha = null;
		public IMethod entry = null;
		public ExternalCallCheck ext = null;
		public String[] immutableNoOut = Main.IMMUTABLE_NO_OUT;
		public String[] immutableStubs = Main.IMMUTABLE_STUBS;
		public String[] ignoreStaticFields = Main.IGNORE_STATIC_FIELDS;
		public ExceptionAnalysis exceptions = ExceptionAnalysis.INTRAPROC;
		public boolean accessPath = false;
		public boolean localKillingDefs = true;
		public boolean keepPhiNodes = true;
		public int prunecg = DO_NOT_PRUNE;
		public PointsToPrecision pts = PointsToPrecision.CONTEXT_SENSITIVE;
		// only used iff pts is set to object sensitive. If null defaults to
		// "do object sensitive analysis for all methods"
		public ObjSensContextSelector.MethodFilter objSensFilter = null;
		public FieldPropagation fieldPropagation = FieldPropagation.FLAT;
		public boolean debugAccessPath = false;
		/*
		 * Turns off control dependency from field access operation to base-pointer node and moves
		 * "nullpointer access exception" control dependency from instruction to basepointer node.
		 * This way data written to the field is no longer connected to the possible exception that
		 * may arise from the base pointer beeing null. This should improve precision.
		 *
		 * v1.f = v2
		 *
		 * Old style:
		 *
		 * [v1] -(dd)-> [set f] <-(dd)- [v2]
		 *   ^           | | |            ^
		 *   \--(cd)-----/ | \-----(cd)---/
		 *               (cd)
		 *               / |
		 *           [exc or normal]
		 *
		 * New style:
		 *
		 * [v1] -(dd)-> [set f] <-(dd)- [v2]
		 *   |              |            ^
		 *  (cd)            \------(cd)--/
		 *  / |
		 * [exc or normal]
		 *
		 */
		public boolean noBasePointerDependency = true;
		public String debugAccessPathOutputDir = null;
		public boolean debugCallGraphDotOutput = false;
		public boolean debugManyGraphsDotOutput = false; // CFG, CDG, PDG, DDG
		public StaticInitializationTreatment staticInitializers = StaticInitializationTreatment.NONE;
		public boolean debugStaticInitializers = false;
		public boolean computeInterference = true;
		public boolean computeSummary = true;
	}

	public String getMainMethodName() {
		return Util.methodName(getMainPDG().getMethod());
	}

	public TIntSet getAllocationNodes(PDGNode n) {
		assert cfg.computeInterference : "Only computed for calls to Thread.start when interference is on.";
		return (call2alloc != null && call2alloc.containsKey(n) ? call2alloc.get(n) : null);
	}

	public PDG createAndAddPDG(final CGNode cgm, final IProgressMonitor progress) throws UnsoundGraphException,
			CancelException {
		final PDG pdg = PDG.build(this, Util.methodName(cgm.getMethod()), cgm, pdgId, cfg.ext, cfg.out, progress);
		pdgId++;
		pdgs.add(pdg);

		return pdg;
	}

	public long countNodes() {
		long count = 0;

		for (final PDG pdg : pdgs) {
			count += pdg.vertexSet().size();
		}

		return count;
	}

	private IFieldsMayMod fieldsMayMod;

	public void registerFinalModRef(final ModRefCandidates mrefs, final IProgressMonitor progress) throws CancelException {
		if (!cfg.localKillingDefs) {
			throw new IllegalStateException("Local killing definfitions is not activated.");
		}

		fieldsMayMod = FieldsMayModComputation.create(this, mrefs, progress);
	}

	public IFieldsMayMod getFieldsMayMod() {
		if (!cfg.localKillingDefs) {
			throw new IllegalStateException("Local killing definfitions is not activated.");
		}

		if (fieldsMayMod == null) {
			// default to simple always true may mod, if none has been set - e.g. when flat params is used and no
			// modref candidates are computed
			fieldsMayMod = new SimpleFieldsMayMod();
		}

		return fieldsMayMod;
	}

}
