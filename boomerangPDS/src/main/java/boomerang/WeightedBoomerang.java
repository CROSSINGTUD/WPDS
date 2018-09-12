/*******************************************************************************
 * Copyright (c) 2018 Fraunhofer IEM, Paderborn, Germany.
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *  
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Johannes Spaeth - initial API and implementation
 *******************************************************************************/
package boomerang;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.google.common.collect.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Stopwatch;

import boomerang.callgraph.BackwardsObservableICFG;
import boomerang.callgraph.CallerListener;
import boomerang.callgraph.ObservableICFG;
import boomerang.customize.BackwardEmptyCalleeFlow;
import boomerang.customize.EmptyCalleeFlow;
import boomerang.customize.ForwardEmptyCalleeFlow;
import boomerang.debugger.Debugger;
import boomerang.jimple.AllocVal;
import boomerang.jimple.Field;
import boomerang.jimple.Statement;
import boomerang.jimple.StaticFieldVal;
import boomerang.jimple.Val;
import boomerang.poi.AbstractPOI;
import boomerang.poi.BaseSolverContext;
import boomerang.poi.ExecuteImportFieldStmtPOI;
import boomerang.poi.PointOfIndirection;
import boomerang.results.BackwardBoomerangResults;
import boomerang.results.ForwardBoomerangResults;
import boomerang.seedfactory.SimpleSeedFactory;
import boomerang.solver.AbstractBoomerangSolver;
import boomerang.solver.BackwardBoomerangSolver;
import boomerang.solver.ForwardBoomerangSolver;
import boomerang.solver.ReachableMethodListener;
import boomerang.stats.IBoomerangStats;
import heros.utilities.DefaultValueMap;
import soot.Local;
import soot.Scene;
import soot.SootClass;
import soot.SootField;
import soot.SootMethod;
import soot.Unit;
import soot.jimple.ArrayRef;
import soot.jimple.AssignStmt;
import soot.jimple.InstanceFieldRef;
import soot.jimple.InvokeExpr;
import soot.jimple.NewMultiArrayExpr;
import soot.jimple.Stmt;
import soot.jimple.StringConstant;
import sync.pds.solver.SyncPDSUpdateListener;
import sync.pds.solver.WeightFunctions;
import sync.pds.solver.WitnessNode;
import sync.pds.solver.nodes.GeneratedState;
import sync.pds.solver.nodes.INode;
import sync.pds.solver.nodes.Node;
import sync.pds.solver.nodes.SingleNode;
import wpds.impl.NestedWeightedPAutomatons;
import wpds.impl.SummaryNestedWeightedPAutomatons;
import wpds.impl.Transition;
import wpds.impl.UnbalancedPopListener;
import wpds.impl.Weight;
import wpds.impl.WeightedPAutomaton;
import wpds.interfaces.State;
import wpds.interfaces.WPAStateListener;
import wpds.interfaces.WPAUpdateListener;

public abstract class WeightedBoomerang<W extends Weight> {
	public static final boolean DEBUG = false;
	protected ObservableICFG<Unit, SootMethod> icfg;
	private static final Logger logger = LogManager.getLogger();
	private Map<Entry<INode<Node<Statement, Val>>, Field>, INode<Node<Statement, Val>>> genField = new HashMap<>();
	private long lastTick;
	private IBoomerangStats<W> stats;
	private List<SolverCreationListener<W>> solverCreationListeners = Lists.newArrayList();
	private Multimap<SolverPair, ExecuteImportFieldStmtPOI<W>> poiListeners = HashMultimap.create();
	private Multimap<SolverPair,INode<Node<Statement,Val>>> activatedPoi = HashMultimap.create();
	private final DefaultValueMap<Query, AbstractBoomerangSolver<W>> queryToSolvers = new DefaultValueMap<Query, AbstractBoomerangSolver<W>>() {

		@Override
		protected AbstractBoomerangSolver<W> createItem(final Query key) {
			final AbstractBoomerangSolver<W> solver;
			if (key instanceof BackwardQuery) {
				if (DEBUG)
					System.out.println("Backward solving query: " + key);
				solver = createBackwardSolver((BackwardQuery) key);
			} else {
				if (DEBUG)
					System.out.println("Forward solving query: " + key);
				solver = createForwardSolver((ForwardQuery) key);
			}
			solver.getCallAutomaton()
					.registerUnbalancedPopListener(new UnbalancedPopListener<Statement, INode<Val>, W>() {

						@Override
						public void unbalancedPop(final INode<Val> returningFact,
								final Transition<Statement, INode<Val>> trans, final W weight) {
							Statement exitStmt = trans.getLabel();
							SootMethod callee = exitStmt.getMethod();
							if (!callee.isStaticInitializer()) {
                                UnbalancedPopHandler<W> info = new UnbalancedPopHandler<W>(returningFact, trans,weight);
                                UnbalancedPopCallerListener listener = new UnbalancedPopCallerListener(callee, info,
										scopedQueries, solver, key);
                                icfg().addCallerListener(listener);
						} else {
							for (SootMethod entryPoint : Scene.v().getEntryPoints()) {
								for (Unit ep : WeightedBoomerang.this.icfg().getStartPointsOf(entryPoint)) {
									final Statement callStatement = new Statement((Stmt) ep,
											WeightedBoomerang.this.icfg().getMethodOf(ep));
									solver.submit(callStatement.getMethod(), new Runnable() {
										@Override
										public void run() {

													Node<Statement, Val> returnedVal = new Node<Statement, Val>(
															callStatement, returningFact.fact());
													solver.setCallingContextReachable(returnedVal);
													solver.getCallAutomaton()
															.addWeightForTransition(
																	new Transition<Statement, INode<Val>>(returningFact,
																			callStatement,
																			solver.getCallAutomaton().getInitialState()),
																	weight);

												}
											});
										}
									}
								}
						}


					});
			stats.registerSolver(key, solver);
			solver.registerListener(new SyncPDSUpdateListener<Statement, Val, Field>() {

				@Override
				public void onReachableNodeAdded(WitnessNode<Statement, Val, Field> reachableNode) {
					if (options.analysisTimeoutMS() > 0) {
						long elapsed = analysisWatch.elapsed(TimeUnit.MILLISECONDS);
						if (elapsed - lastTick > 15000) {
							// System.err.println(stats);
							lastTick = elapsed;
						}
						if (options.analysisTimeoutMS() < elapsed) {
							if (analysisWatch.isRunning())
								analysisWatch.stop();
							throw new BoomerangTimeoutException(elapsed, stats);
						}
					}
				}
			});

			onCreateSubSolver(solver);
			return solver;
		}
	};

	private class UnbalancedPopCallerListener implements CallerListener<Unit, SootMethod>{
		private SootMethod callee;
		UnbalancedPopHandler<W> info;
		Set<BackwardQuery> scopedQueries;
		AbstractBoomerangSolver<W> solver;
		Query key;

		UnbalancedPopCallerListener(SootMethod callee, UnbalancedPopHandler<W> info, Set<BackwardQuery> scopedQueries,
									AbstractBoomerangSolver<W> solver, Query key){
			this.callee = callee;
			this.info = info;
			this.scopedQueries = scopedQueries;
			this.solver = solver;
			this.key =key;
		}

		@Override
		public SootMethod getObservedCallee() {
			return callee;
		}

		@Override
		public void onCallerAdded(Unit callSite, SootMethod sootMethod) {
			if (!((Stmt) callSite).containsInvokeExpr())
				return;
			final Statement callStatement = new Statement((Stmt) callSite,
					WeightedBoomerang.this.icfg().getMethodOf(callSite));
			Node<Statement, AbstractBoomerangSolver<W>> solverPair = new Node<>(callStatement, solver);
			registerUnbalancedPopListener(solverPair, info);
			if (callee.isStatic() || !scopedQueries.contains(key)) {
				triggerUnbalancedPop(solverPair);
			}
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			UnbalancedPopCallerListener listener = (UnbalancedPopCallerListener) o;
			return Objects.equals(callee, listener.callee) &&
					Objects.equals(info, listener.info) &&
					Objects.equals(scopedQueries, listener.scopedQueries) &&
					Objects.equals(solver, listener.solver) &&
					Objects.equals(key, listener.key);
		}

		@Override
		public int hashCode() {
			return Objects.hash(callee, info, scopedQueries, solver, key);
		}
	}

	private void registerUnbalancedPopListener(
			Node<Statement,AbstractBoomerangSolver<W>> unbalancedPopPair, UnbalancedPopHandler<W> unbalancedPopInfo) {
		if(unbalancedPopPairs.contains(unbalancedPopPair)) {
			unbalancedPopInfo.trigger(unbalancedPopPair.stmt(),unbalancedPopPair.fact());
		}else {
			unbalancedListeners.put(unbalancedPopPair, unbalancedPopInfo);
		}

	}

	Multimap<Node<Statement,AbstractBoomerangSolver<W>>,UnbalancedPopHandler<W>> unbalancedListeners = HashMultimap.create();
	Set<Node<Statement,AbstractBoomerangSolver<W>>> unbalancedPopPairs = Sets.newHashSet();

	private void triggerUnbalancedPop(Node<Statement,AbstractBoomerangSolver<W>> unbalancedPopPair) {
		if(unbalancedPopPairs.add(unbalancedPopPair)) {
			for(UnbalancedPopHandler<W> unbalancedPopInfo : unbalancedListeners.get(unbalancedPopPair)) {
				unbalancedPopInfo.trigger(unbalancedPopPair.stmt(),unbalancedPopPair.fact());
			}
		}
	}





	private ObservableICFG<Unit, SootMethod> bwicfg;
	private EmptyCalleeFlow forwardEmptyCalleeFlow = new ForwardEmptyCalleeFlow();
	private EmptyCalleeFlow backwardEmptyCalleeFlow = new BackwardEmptyCalleeFlow();

	private NestedWeightedPAutomatons<Statement, INode<Val>, W> backwardCallSummaries = new SummaryNestedWeightedPAutomatons<>();
	private NestedWeightedPAutomatons<Field, INode<Node<Statement, Val>>, W> backwardFieldSummaries = new SummaryNestedWeightedPAutomatons<>();
	private NestedWeightedPAutomatons<Statement, INode<Val>, W> forwardCallSummaries = new SummaryNestedWeightedPAutomatons<>();
	private NestedWeightedPAutomatons<Field, INode<Node<Statement, Val>>, W> forwardFieldSummaries = new SummaryNestedWeightedPAutomatons<>();
	private DefaultValueMap<FieldWritePOI, FieldWritePOI> fieldWrites = new DefaultValueMap<FieldWritePOI, FieldWritePOI>() {
		@Override
		protected FieldWritePOI createItem(FieldWritePOI key) {
			stats.registerFieldWritePOI(key);
			return key;
		}
	};
	private DefaultValueMap<FieldReadPOI, FieldReadPOI> fieldReads = new DefaultValueMap<FieldReadPOI, FieldReadPOI>() {
		@Override
		protected FieldReadPOI createItem(FieldReadPOI key) {
			stats.registerFieldReadPOI(key);
			return key;
		}
	};
	protected final BoomerangOptions options;
	private Debugger<W> debugger;
	private Stopwatch analysisWatch = Stopwatch.createUnstarted();
	private Set<BackwardQuery> scopedQueries = Sets.newHashSet();

	public WeightedBoomerang(BoomerangOptions options) {
		this.options = options;
		this.stats = options.statsFactory();
	}

	public WeightedBoomerang() {
		this(new DefaultBoomerangOptions());
	}

	protected AbstractBoomerangSolver<W> createBackwardSolver(final BackwardQuery backwardQuery) {
		final BackwardBoomerangSolver<W> solver = new BackwardBoomerangSolver<W>(bwicfg(), backwardQuery, genField,
				options, createCallSummaries(backwardQuery, backwardCallSummaries),
				createFieldSummaries(backwardQuery, backwardFieldSummaries)) {

			@Override
			protected void callBypass(Statement callSite, Statement returnSite, Val value) {
			}

			@Override
			protected Collection<? extends State> computeCallFlow(SootMethod caller, Statement returnSite,
					Statement callSite, InvokeExpr invokeExpr, Val fact, SootMethod callee, Stmt calleeSp) {
				return super.computeCallFlow(caller, returnSite, callSite, invokeExpr, fact, callee, calleeSp);
			}

			@Override
			protected void onCallFlow(SootMethod callee, Stmt callSite, Val value, Collection<? extends State> res) {
			}

			@Override
			protected Collection<? extends State> getEmptyCalleeFlow(SootMethod caller, Stmt callSite, Val value,
					Stmt returnSite) {
				return backwardEmptyCalleeFlow.getEmptyCalleeFlow(caller, callSite, value, returnSite);
			}

			@Override
			protected WeightFunctions<Statement, Val, Field, W> getFieldWeights() {
				return WeightedBoomerang.this.getBackwardFieldWeights();
			}

			@Override
			protected WeightFunctions<Statement, Val, Statement, W> getCallWeights() {
				return WeightedBoomerang.this.getBackwardCallWeights();
			}

		};
		solver.registerListener(new SyncPDSUpdateListener<Statement, Val, Field>() {
			@Override
			public void onReachableNodeAdded(WitnessNode<Statement, Val, Field> node) {
				if (hasNoMethod(node)) {
					return;
				}
				Optional<AllocVal> allocNode = isAllocationNode(node.stmt(), node.fact());
				if (allocNode.isPresent()) {
					ForwardQuery q = new ForwardQuery(node.stmt(), allocNode.get());
					final AbstractBoomerangSolver<W> forwardSolver = forwardSolve(q);
					solver.registerReachableMethodListener(new ReachableMethodListener<W>() {

						@Override
						public void reachable(SootMethod m) {
							forwardSolver.addReachable(m);
						}
					});
				}
				if (isFieldStore(node.stmt())) {
				} else if (isArrayLoad(node.stmt())) {
					backwardHandleFieldRead(node, createArrayFieldLoad(node.stmt()), backwardQuery);
				} else if (isFieldLoad(node.stmt())) {
					backwardHandleFieldRead(node, createFieldLoad(node.stmt()), backwardQuery);
				}
				if (isBackwardEnterCall(node.stmt())) {
					//TODO
				}
				if (isFirstStatementOfEntryPoint(node.stmt()) && node.fact().isStatic()) {
					StaticFieldVal val = (StaticFieldVal) node.fact();
					for (SootMethod m : val.field().getDeclaringClass().getMethods()) {
						if (m.isStaticInitializer()) {
							solver.addReachable(m);
							for (Unit ep : icfg().getEndPointsOf(m)) {
								StaticFieldVal newVal = new StaticFieldVal(val.value(), val.field(), m);
								solver.addNormalCallFlow(node.asNode(),
										new Node<Statement, Val>(new Statement((Stmt) ep, m), newVal));
								solver.addNormalFieldFlow(node.asNode(),
										new Node<Statement, Val>(new Statement((Stmt) ep, m), newVal));
							}
						}
					}
				}
			}

		});

		return solver;
	}

	protected boolean hasNoMethod(WitnessNode<Statement, Val, Field> node) {
		if (icfg().getMethodOf(node.stmt().getUnit().get()) == null) {
			return true;
		}
		return false;
	}

	protected boolean isFirstStatementOfEntryPoint(Statement stmt) {
		for (SootMethod m : Scene.v().getEntryPoints()) {
			if (m.hasActiveBody()) {
				if (stmt.equals(new Statement((Stmt) m.getActiveBody().getUnits().getFirst(), m))) {
					return true;
				}
			}
		}
		return false;
	}


	protected boolean isBackwardEnterCall(Statement stmt) {
		if (!stmt.getUnit().isPresent())
			return false;
		try {
			return icfg().isExitStmt(stmt.getUnit().get());
		} catch (NullPointerException e) {
			return false;
		}
	}

	protected Optional<AllocVal> isAllocationNode(Statement s, Val fact) {
		Optional<Stmt> optUnit = s.getUnit();
		if (optUnit.isPresent()) {
			Stmt stmt = optUnit.get();
			return options.getAllocationVal(s.getMethod(), stmt, fact, icfg());
		}
		return Optional.absent();
	}

	protected ForwardBoomerangSolver<W> createForwardSolver(final ForwardQuery sourceQuery) {
		final ForwardBoomerangSolver<W> solver = new ForwardBoomerangSolver<W>(icfg(), sourceQuery, genField, options,
				createCallSummaries(sourceQuery, forwardCallSummaries),
				createFieldSummaries(sourceQuery, forwardFieldSummaries)) {

			@Override
			protected void callBypass(Statement callSite, Statement returnSite, Val value) {
					return;
			}

			@Override
			protected Collection<? extends State> getEmptyCalleeFlow(SootMethod caller, Stmt callSite, Val value,
					Stmt returnSite) {
				return forwardEmptyCalleeFlow.getEmptyCalleeFlow(caller, callSite, value, returnSite);
			}

			@Override
			protected WeightFunctions<Statement, Val, Statement, W> getCallWeights() {
				return WeightedBoomerang.this.getForwardCallWeights(sourceQuery);
			}

			@Override
			protected WeightFunctions<Statement, Val, Field, W> getFieldWeights() {
				return WeightedBoomerang.this.getForwardFieldWeights();
			}

		};

		solver.registerListener(new SyncPDSUpdateListener<Statement, Val, Field>() {
			@Override
			public void onReachableNodeAdded(WitnessNode<Statement, Val, Field> node) {
				if (hasNoMethod(node)) {
					return;
				}
				if (isFieldStore(node.stmt())) {
					forwardHandleFieldWrite(node, createFieldStore(node.stmt()), sourceQuery);
				} else if (isArrayStore(node.stmt())) {
					if (options.arrayFlows()) {
						forwardHandleFieldWrite(node, createArrayFieldStore(node.stmt()), sourceQuery);
					}
				} else if (isFieldLoad(node.stmt())) {
					forwardHandleFieldLoad(node, createFieldLoad(node.stmt()), sourceQuery);
				} else if (isArrayLoad(node.stmt())) {
					forwardHandleFieldLoad(node, createArrayFieldLoad(node.stmt()), sourceQuery);
				}
				if (isBackwardEnterCall(node.stmt())) {
					//TODO
				}
			}
		});
		

		return solver;
	}

	private NestedWeightedPAutomatons<Statement, INode<Val>, W> createCallSummaries(final Query sourceQuery,
			final NestedWeightedPAutomatons<Statement, INode<Val>, W> summaries) {
		return new NestedWeightedPAutomatons<Statement, INode<Val>, W>() {

			@Override
			public void putSummaryAutomaton(INode<Val> target, WeightedPAutomaton<Statement, INode<Val>, W> aut) {
				summaries.putSummaryAutomaton(target, aut);
			}

			@Override
			public WeightedPAutomaton<Statement, INode<Val>, W> getSummaryAutomaton(INode<Val> target) {
				if (target.fact().equals(sourceQuery.var())) {
					return queryToSolvers.getOrCreate(sourceQuery).getCallAutomaton();
				}
				return summaries.getSummaryAutomaton(target);
			}
		};
	}

	private NestedWeightedPAutomatons<Field, INode<Node<Statement, Val>>, W> createFieldSummaries(final Query query,
			final NestedWeightedPAutomatons<Field, INode<Node<Statement, Val>>, W> summaries) {
		return new NestedWeightedPAutomatons<Field, INode<Node<Statement, Val>>, W>() {

			@Override
			public void putSummaryAutomaton(INode<Node<Statement, Val>> target,
					WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> aut) {
				summaries.putSummaryAutomaton(target, aut);
			}

			@Override
			public WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> getSummaryAutomaton(
					INode<Node<Statement, Val>> target) {
				if (target.fact().equals(query.asNode())) {
					return queryToSolvers.getOrCreate(query).getFieldAutomaton();
				}
				return summaries.getSummaryAutomaton(target);
			}

		};
	}

	protected FieldReadPOI createFieldLoad(Statement s) {
		Stmt stmt = s.getUnit().get();
		AssignStmt as = (AssignStmt) stmt;
		InstanceFieldRef ifr = (InstanceFieldRef) as.getRightOp();
		Val base = new Val(ifr.getBase(), icfg().getMethodOf(as));
		Field field = new Field(ifr.getField());
		return fieldReads
				.getOrCreate(new FieldReadPOI(s, base, field, new Val(as.getLeftOp(), icfg().getMethodOf(as))));
	}

	protected FieldReadPOI createArrayFieldLoad(Statement s) {
		Stmt stmt = s.getUnit().get();
		AssignStmt as = (AssignStmt) stmt;
		ArrayRef ifr = (ArrayRef) as.getRightOp();
		Val base = new Val(ifr.getBase(), icfg().getMethodOf(as));
		Val stored = new Val(as.getLeftOp(), icfg().getMethodOf(as));
		return fieldReads.getOrCreate(new FieldReadPOI(s, base, Field.array(), stored));
	}

	protected FieldWritePOI createArrayFieldStore(Statement s) {
		Stmt stmt = s.getUnit().get();
		AssignStmt as = (AssignStmt) stmt;
		ArrayRef ifr = (ArrayRef) as.getLeftOp();
		Val base = new Val(ifr.getBase(), icfg().getMethodOf(as));
		Val stored = new Val(as.getRightOp(), icfg().getMethodOf(as));
		return fieldWrites.getOrCreate(new FieldWritePOI(s, base, Field.array(), stored));
	}

	protected FieldWritePOI createFieldStore(Statement s) {
		Stmt stmt = s.getUnit().get();
		AssignStmt as = (AssignStmt) stmt;
		InstanceFieldRef ifr = (InstanceFieldRef) as.getLeftOp();
		Val base = new Val(ifr.getBase(), icfg().getMethodOf(as));
		Val stored = new Val(as.getRightOp(), icfg().getMethodOf(as));
		Field field = new Field(ifr.getField());
		return fieldWrites.getOrCreate(new FieldWritePOI(s, base, field, stored));
	}

	public static boolean isFieldStore(Statement s) {
		Optional<Stmt> optUnit = s.getUnit();
		if (optUnit.isPresent()) {
			Stmt stmt = optUnit.get();
			if (stmt instanceof AssignStmt && ((AssignStmt) stmt).getLeftOp() instanceof InstanceFieldRef) {
				return true;
			}
		}
		return false;
	}

	public static boolean isArrayStore(Statement s) {
		Optional<Stmt> optUnit = s.getUnit();
		if (optUnit.isPresent()) {
			Stmt stmt = optUnit.get();
			if (stmt instanceof AssignStmt && ((AssignStmt) stmt).getLeftOp() instanceof ArrayRef) {
				return true;
			}
		}
		return false;
	}

	public static boolean isArrayLoad(Statement s) {
		Optional<Stmt> optUnit = s.getUnit();
		if (optUnit.isPresent()) {
			Stmt stmt = optUnit.get();
			if (stmt instanceof AssignStmt && ((AssignStmt) stmt).getRightOp() instanceof ArrayRef) {
				return true;
			}
		}
		return false;
	}

	public static boolean isFieldLoad(Statement s) {
		Optional<Stmt> optUnit = s.getUnit();
		if (optUnit.isPresent()) {
			Stmt stmt = optUnit.get();
			if (stmt instanceof AssignStmt && ((AssignStmt) stmt).getRightOp() instanceof InstanceFieldRef) {
				return true;
			}
		}
		return false;
	}

	protected void backwardHandleFieldRead(final WitnessNode<Statement, Val, Field> node, FieldReadPOI fieldRead,
			final BackwardQuery sourceQuery) {
		if (node.fact().equals(fieldRead.getStoredVar())) {
			fieldRead.addFlowAllocation(sourceQuery);
		}
	}

	protected void forwardHandleFieldWrite(final WitnessNode<Statement, Val, Field> node,
			final FieldWritePOI fieldWritePoi, final ForwardQuery sourceQuery) {
		BackwardQuery backwardQuery = new BackwardQuery(node.stmt(), fieldWritePoi.getBaseVar());
		if (node.fact().equals(fieldWritePoi.getStoredVar())) {
			backwardSolveUnderScope(backwardQuery, sourceQuery, node);
			queryToSolvers.get(sourceQuery).getFieldAutomaton().registerListener(new ForwardHandleFieldWrite(sourceQuery, fieldWritePoi, node.stmt()));
		}
		if (node.fact().equals(fieldWritePoi.getBaseVar())) {
			queryToSolvers.getOrCreate(sourceQuery).getFieldAutomaton().registerListener(
					new TriggerBaseAllocationAtFieldWrite(new SingleNode<Node<Statement, Val>>(node.asNode()),
							fieldWritePoi, sourceQuery));
		}
	}

	private void backwardSolveUnderScope(BackwardQuery backwardQuery, ForwardQuery forwardQuery, WitnessNode<Statement, Val, Field> node) {
		scopedQueries.add(backwardQuery);
		backwardSolve(backwardQuery);
		final AbstractBoomerangSolver<W> bwSolver = queryToSolvers.getOrCreate(backwardQuery);
		AbstractBoomerangSolver<W> fwSolver = queryToSolvers.getOrCreate(forwardQuery);
		fwSolver.registerReachableMethodListener(new ReachableMethodListener<W>() {
			@Override
			public void reachable(SootMethod m) {
				bwSolver.addReachable(m);
			}
		});


		fwSolver.getCallAutomaton().registerListener(new BaseSolverContext<W>(fwSolver, new SingleNode<>(node.fact()), node.stmt(), fwSolver) {

			@Override
			public void anyContext() {
				AnyContextCallerListener listener = new AnyContextCallerListener(node.stmt().getMethod(), bwSolver);
				icfg().addCallerListener(listener);
			}

			@Override
			public void callSiteFound(Statement callSite) {
				for(Statement realCall : fwSolver.getPredsOf(callSite)){
					if(realCall.isCallsite()) {
						triggerUnbalancedPop(new Node<Statement,AbstractBoomerangSolver<W>>(realCall,bwSolver));
					}
				}
			}});
	}

	class AnyContextCallerListener implements CallerListener<Unit, SootMethod>{
		SootMethod callee;
		AbstractBoomerangSolver<W> bwSolver;

		AnyContextCallerListener(SootMethod callee, AbstractBoomerangSolver<W> bwSolver){
			this.callee = callee;
			this.bwSolver = bwSolver;
		}

		@Override
		public SootMethod getObservedCallee() {
			return callee;
		}

		@Override
		public void onCallerAdded(Unit callSite, SootMethod sootMethod) {
			if (!((Stmt) callSite).containsInvokeExpr())
				return;
			final Statement callStatement = new Statement((Stmt) callSite,
					WeightedBoomerang.this.icfg().getMethodOf(callSite));
			Node<Statement,AbstractBoomerangSolver<W>> solverPair = new Node<>(callStatement,bwSolver);
			triggerUnbalancedPop(solverPair);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			AnyContextCallerListener listener = (AnyContextCallerListener) o;
			return Objects.equals(callee, listener.callee) &&
					Objects.equals(bwSolver, listener.bwSolver);
		}

		@Override
		public int hashCode() {
			return Objects.hash(callee, bwSolver);
		}
	}

	private final class ForwardHandleFieldWrite implements WPAUpdateListener<Field, INode<Node<Statement, Val>>, W> {
		private final Query sourceQuery;
		private final AbstractPOI<Statement, Val, Field> fieldWritePoi;
		private final Statement stmt;

		private ForwardHandleFieldWrite(Query sourceQuery, AbstractPOI<Statement, Val, Field> fieldWritePoi,
				Statement statement) {
			this.sourceQuery = sourceQuery;
			this.fieldWritePoi = fieldWritePoi;
			this.stmt = statement;
		}

		@Override
		public void onWeightAdded(Transition<Field, INode<Node<Statement, Val>>> t, W w,
				WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> aut) {
			if(t.getStart() instanceof GeneratedState)
				return;
			if(t.getStart().fact().stmt().equals(stmt) && t.getLabel().equals(Field.empty())){
				fieldWritePoi.addFlowAllocation(sourceQuery);
			}
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + getOuterType().hashCode();
			result = prime * result + ((sourceQuery == null) ? 0 : sourceQuery.hashCode());
			result = prime * result + ((stmt == null) ? 0 : stmt.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			ForwardHandleFieldWrite other = (ForwardHandleFieldWrite) obj;
			if (!getOuterType().equals(other.getOuterType()))
				return false;
			if (sourceQuery == null) {
				if (other.sourceQuery != null)
					return false;
			} else if (!sourceQuery.equals(other.sourceQuery))
				return false;
			if (stmt == null) {
				if (other.stmt != null)
					return false;
			} else if (!stmt.equals(other.stmt))
				return false;
			return true;
		}

		private WeightedBoomerang getOuterType() {
			return WeightedBoomerang.this;
		}
		
	}


	private class TriggerBaseAllocationAtFieldWrite extends WPAStateListener<Field, INode<Node<Statement, Val>>, W> {

		private final PointOfIndirection<Statement, Val, Field> fieldWritePoi;
		private final ForwardQuery sourceQuery;

		public TriggerBaseAllocationAtFieldWrite(INode<Node<Statement, Val>> state,
				PointOfIndirection<Statement, Val, Field> fieldWritePoi, ForwardQuery sourceQuery) {
			super(state);
			this.fieldWritePoi = fieldWritePoi;
			this.sourceQuery = sourceQuery;
		}

		@Override
		public void onOutTransitionAdded(Transition<Field, INode<Node<Statement, Val>>> t, W w,
				WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> aut) {
			if (isAllocationNode(t.getTarget().fact().fact(), sourceQuery)) {
				fieldWritePoi.addBaseAllocation(sourceQuery);
			}
		}

		@Override
		public void onInTransitionAdded(Transition<Field, INode<Node<Statement, Val>>> t, W w,
				WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> aut) {
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = super.hashCode();
			result = prime * result + getOuterType().hashCode();
			result = prime * result + ((fieldWritePoi == null) ? 0 : fieldWritePoi.hashCode());
			result = prime * result + ((sourceQuery == null) ? 0 : sourceQuery.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (!super.equals(obj))
				return false;
			if (getClass() != obj.getClass())
				return false;
			TriggerBaseAllocationAtFieldWrite other = (TriggerBaseAllocationAtFieldWrite) obj;
			if (!getOuterType().equals(other.getOuterType()))
				return false;
			if (fieldWritePoi == null) {
				if (other.fieldWritePoi != null)
					return false;
			} else if (!fieldWritePoi.equals(other.fieldWritePoi))
				return false;
			if (sourceQuery == null) {
				if (other.sourceQuery != null)
					return false;
			} else if (!sourceQuery.equals(other.sourceQuery))
				return false;
			return true;
		}

		private WeightedBoomerang getOuterType() {
			return WeightedBoomerang.this;
		}

	}

	private void forwardHandleFieldLoad(final WitnessNode<Statement, Val, Field> node, final FieldReadPOI fieldReadPoi,
			final ForwardQuery sourceQuery) {
		if (node.fact().equals(fieldReadPoi.getBaseVar())) {
			queryToSolvers.getOrCreate(sourceQuery).getFieldAutomaton().registerListener(
					new TriggerBaseAllocationAtFieldWrite(new SingleNode<Node<Statement, Val>>(node.asNode()),
							fieldReadPoi, sourceQuery));
		}
	}

	private boolean isAllocationNode(Val fact, ForwardQuery sourceQuery) {
		return fact.equals(sourceQuery.var());
	}

	private ObservableICFG<Unit, SootMethod> bwicfg() {
		if (bwicfg == null)
			bwicfg = new BackwardsObservableICFG(icfg());
		return bwicfg;
	}

	public ForwardBoomerangResults<W> solve(ForwardQuery query) {
		if (!analysisWatch.isRunning()) {
			analysisWatch.start();
		}
		boolean timedout = false;
		try {
			logger.debug("Starting forward analysis of: {}", query);
			forwardSolve(query);
			logger.debug("Terminated forward analysis of: {}", query);
		} catch (BoomerangTimeoutException e) {
			timedout = true;
			logger.debug("Timeout of query: {}", query);
		}

		if (analysisWatch.isRunning()) {
			analysisWatch.stop();
		}
		return new ForwardBoomerangResults<W>(query, timedout, this.queryToSolvers, icfg(), bwicfg(), getStats(),
				analysisWatch);
	}

	public BackwardBoomerangResults<W> solve(BackwardQuery query) {
		if (!analysisWatch.isRunning()) {
			analysisWatch.start();
		}
		boolean timedout = false;
		try {
			logger.debug("Starting backward analysis of: {}", query);
			backwardSolve(query);
			logger.debug("Terminated backward analysis of: {}", query);
		} catch (BoomerangTimeoutException e) {
			timedout = true;
			logger.debug("Timeout of query: {}", query);
		}
		if (analysisWatch.isRunning()) {
			analysisWatch.stop();
		}

		return new BackwardBoomerangResults<W>(query, timedout, this.queryToSolvers, getStats(), analysisWatch);
	}

	protected void backwardSolve(BackwardQuery query) {
		if (!options.aliasing())
			return;
		Optional<Stmt> unit = query.asNode().stmt().getUnit();
		AbstractBoomerangSolver<W> solver = queryToSolvers.getOrCreate(query);
		if (unit.isPresent()) {
			for (Unit succ : bwicfg().getSuccsOf(unit.get())) {
				solver.solve(new Node<Statement, Val>(new Statement((Stmt) succ, icfg().getMethodOf(succ)),
						query.asNode().fact()));
			}
		}
	}

	private AbstractBoomerangSolver<W> forwardSolve(ForwardQuery query) {
		Optional<Stmt> unit = query.asNode().stmt().getUnit();
		AbstractBoomerangSolver<W> solver = queryToSolvers.getOrCreate(query);
		if (unit.isPresent()) {
			for (Unit succ : icfg().getSuccsOf(unit.get())) {
				Node<Statement, Val> source = new Node<Statement, Val>(
						new Statement((Stmt) succ, icfg().getMethodOf(succ)), query.asNode().fact());
				if (isMultiArrayAllocation(unit.get()) && options.arrayFlows()) {
					// TODO fix; adjust as below;
					SingleNode<Node<Statement, Val>> sourveVal = new SingleNode<Node<Statement, Val>>(source);
					GeneratedState<Node<Statement, Val>, Field> genState = new GeneratedState<Node<Statement, Val>, Field>(sourveVal, Field.array());
					insertTransition(solver.getFieldAutomaton(),
							new Transition<Field, INode<Node<Statement, Val>>>(
									sourveVal, Field.array(),
									genState));
					insertTransition(solver.getFieldAutomaton(),
							new Transition<Field, INode<Node<Statement, Val>>>(genState, Field.empty(),
									solver.getFieldAutomaton().getInitialState()));
				}
				if (isStringAllocation(unit.get())) {
					// Scene.v().forceResolve("java.lang.String",
					// SootClass.BODIES);
					SootClass stringClass = Scene.v().getSootClass("java.lang.String");
					if (stringClass.declaresField("char[] value")) {
						SootField valueField = stringClass.getField("char[] value");
						SingleNode<Node<Statement, Val>> s = new SingleNode<Node<Statement, Val>>(source);
						INode<Node<Statement, Val>> irState = solver.getFieldAutomaton().createState(s,
								new Field(valueField));
						insertTransition(solver.getFieldAutomaton(), new Transition<Field, INode<Node<Statement, Val>>>(
								new SingleNode<Node<Statement, Val>>(source), new Field(valueField), irState));
						insertTransition(solver.getFieldAutomaton(), new Transition<Field, INode<Node<Statement, Val>>>(
								irState, Field.empty(), solver.getFieldAutomaton().getInitialState()));
					}
				}
				if (query instanceof WeightedForwardQuery) {
					WeightedForwardQuery<W> q = (WeightedForwardQuery<W>) query;
					solver.solve(source, q.weight());
				} else {
					solver.solve(source);
				}
			}
		}
		return solver;
	}

	private boolean isStringAllocation(Stmt stmt) {
		if (stmt instanceof AssignStmt && ((AssignStmt) stmt).getRightOp() instanceof StringConstant) {
			return true;
		}
		return false;
	}

	private boolean insertTransition(final WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> aut,
			Transition<Field, INode<Node<Statement, Val>>> transition) {
		if (!aut.nested()) {
			return aut.addTransition(transition);
		}
		INode<Node<Statement, Val>> target = transition.getTarget();
		if (!(target instanceof GeneratedState)) {
			forwardFieldSummaries.putSummaryAutomaton(target, aut);

			aut.registerListener(new WPAUpdateListener<Field, INode<Node<Statement, Val>>, W>() {

				@Override
				public void onWeightAdded(Transition<Field, INode<Node<Statement, Val>>> t, W w,
						WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> aut) {
					if (t.getStart() instanceof GeneratedState) {
						WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> n = forwardFieldSummaries
								.getSummaryAutomaton(t.getStart());
						aut.addNestedAutomaton(n);
					}
				}
			});
			return aut.addTransition(transition);
		}
		final WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> nested = forwardFieldSummaries
				.getSummaryAutomaton(target);
		nested.registerListener(new WPAUpdateListener<Field, INode<Node<Statement, Val>>, W>() {

			@Override
			public void onWeightAdded(Transition<Field, INode<Node<Statement, Val>>> t, W w,
					WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> aut) {
				if (t.getStart() instanceof GeneratedState) {
					WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> n = forwardFieldSummaries
							.getSummaryAutomaton(t.getStart());
					aut.addNestedAutomaton(n);
				}
			}
		});
		return nested.addTransition(transition);
	}

	private boolean isMultiArrayAllocation(Stmt stmt) {
		return (stmt instanceof AssignStmt) && ((AssignStmt) stmt).getRightOp() instanceof NewMultiArrayExpr;
	}

	public class FieldWritePOI extends AbstractPOI<Statement, Val, Field> {

		public FieldWritePOI(Statement statement, Val base, Field field, Val stored) {
			super(statement, base, field, stored);
		}

		@Override
		public void execute(final ForwardQuery baseAllocation, final Query flowAllocation) {
			if (flowAllocation instanceof BackwardQuery) {
			} else if (flowAllocation instanceof ForwardQuery) {
				AbstractBoomerangSolver<W> baseSolver = queryToSolvers.get(baseAllocation);
				AbstractBoomerangSolver<W> flowSolver = queryToSolvers.get(flowAllocation);
				for(Statement succ : flowSolver.getSuccsOf(getStmt())) {
					ExecuteImportFieldStmtPOI<W> exec = new ExecuteImportFieldStmtPOI<W>(WeightedBoomerang.this,baseSolver, flowSolver, FieldWritePOI.this, succ){
						public void activate(INode<Node<Statement,Val>> start) {
							activateAllPois(new SolverPair(flowSolver,baseSolver),start);
						};
					};
					registerActivationListener(new SolverPair(flowSolver,baseSolver), exec);
					exec.solve();
				}
			}
		}

	}
	

	public class FieldReadPOI extends AbstractPOI<Statement, Val, Field> {

		public FieldReadPOI(Statement statement, Val base, Field field, Val stored) {
			super(statement, base, field, stored);
		}

		@Override
		public void execute(final ForwardQuery baseAllocation, final Query flowAllocation) {
			if (WeightedBoomerang.this instanceof WholeProgramBoomerang)
				throw new RuntimeException("should not be invoked!");
			if (flowAllocation instanceof ForwardQuery) {
			} else if (flowAllocation instanceof BackwardQuery) {
				AbstractBoomerangSolver<W> baseSolver = queryToSolvers.get(baseAllocation);
				AbstractBoomerangSolver<W> flowSolver = queryToSolvers.get(flowAllocation);
				for(Statement succ : flowSolver.getSuccsOf(getStmt())) {
					ExecuteImportFieldStmtPOI<W> exec = new ExecuteImportFieldStmtPOI<W>(WeightedBoomerang.this,baseSolver, flowSolver, FieldReadPOI.this, succ){
						public void activate(INode<Node<Statement,Val>> start) {
							activateAllPois(new SolverPair(flowSolver,baseSolver),start);
						};
					};
					registerActivationListener(new SolverPair(flowSolver,baseSolver), exec);
					exec.solve();
				}
			}
		}

	}

	protected void activateAllPois(SolverPair pair, INode<Node<Statement, Val>> start) {
		if(activatedPoi.put(pair, start)){
			Collection<ExecuteImportFieldStmtPOI<W>> listeners = poiListeners.get(pair);
			for(ExecuteImportFieldStmtPOI<W> l : Lists.newArrayList(listeners)){
				l.trigger(start);
			}
		}
	}

	public void registerActivationListener(
			WeightedBoomerang<W>.SolverPair solverPair, ExecuteImportFieldStmtPOI<W> exec) {
		Collection<INode<Node<Statement, Val>>> listeners = activatedPoi.get(solverPair);
		for(INode<Node<Statement, Val>> node : Lists.newArrayList(listeners)){
			exec.trigger(node);
		}
		poiListeners.put(solverPair, exec);
	}

	private class SolverPair{

		private AbstractBoomerangSolver<W> flowSolver;
		private AbstractBoomerangSolver<W> baseSolver;

		public SolverPair(AbstractBoomerangSolver<W> flowSolver, AbstractBoomerangSolver<W> baseSolver) {
			this.flowSolver = flowSolver;
			this.baseSolver = baseSolver;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + getOuterType().hashCode();
			result = prime * result + ((baseSolver == null) ? 0 : baseSolver.hashCode());
			result = prime * result + ((flowSolver == null) ? 0 : flowSolver.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			SolverPair other = (SolverPair) obj;
			if (!getOuterType().equals(other.getOuterType()))
				return false;
			if (baseSolver == null) {
				if (other.baseSolver != null)
					return false;
			} else if (!baseSolver.equals(other.baseSolver))
				return false;
			if (flowSolver == null) {
				if (other.flowSolver != null)
					return false;
			} else if (!flowSolver.equals(other.flowSolver))
				return false;
			return true;
		}

		private WeightedBoomerang getOuterType() {
			return WeightedBoomerang.this;
		}
		
	}
	

	public void createPOI(ObservableICFG<Unit, SootMethod> icfg, AbstractBoomerangSolver<W> baseSolver,
			AbstractBoomerangSolver<W> flowSolver, WeightedBoomerang<W>.FieldReadPOI fieldReadPOI, Statement succ) {
		// TODO Auto-generated method stub
		
	}

	public abstract ObservableICFG<Unit, SootMethod> icfg();

	protected abstract WeightFunctions<Statement, Val, Field, W> getForwardFieldWeights();

	protected abstract WeightFunctions<Statement, Val, Field, W> getBackwardFieldWeights();

	protected abstract WeightFunctions<Statement, Val, Statement, W> getBackwardCallWeights();

	protected abstract WeightFunctions<Statement, Val, Statement, W> getForwardCallWeights(ForwardQuery sourceQuery);

	public DefaultValueMap<Query, AbstractBoomerangSolver<W>> getSolvers() {
		return queryToSolvers;
	}

	public abstract Debugger<W> createDebugger();

	public void debugOutput() {
		// for (Query q : queryToSolvers.keySet()) {
		// System.out.println(q +" Nodes: " +
		// queryToSolvers.getOrCreate(q).getReachedStates().size());
		// System.out.println(q +" Field Aut: " +
		// queryToSolvers.getOrCreate(q).getFieldAutomaton().getTransitions().size());
		// System.out.println(q +" Field Aut (failed Additions): " +
		// queryToSolvers.getOrCreate(q).getFieldAutomaton().failedAdditions);
		// System.out.println(q +" Field Aut (failed Direct Additions): " +
		// queryToSolvers.getOrCreate(q).getFieldAutomaton().failedDirectAdditions);
		// System.out.println(q +" Call Aut: " +
		// queryToSolvers.getOrCreate(q).getCallAutomaton().getTransitions().size());
		// System.out.println(q +" Call Aut (failed Additions): " +
		// queryToSolvers.getOrCreate(q).getCallAutomaton().failedAdditions);
		// }

		if (!DEBUG)
			return;

        Debugger<W> debugger = getOrCreateDebugger();
        debugger.done(queryToSolvers);
		int totalRules = 0;
		for (Query q : queryToSolvers.keySet()) {
			totalRules += queryToSolvers.getOrCreate(q).getNumberOfRules();
		}
		logger.debug("Total number of rules: " + totalRules);
		for (Query q : queryToSolvers.keySet()) {
			logger.debug("========================");
			logger.debug(q);
			logger.debug("========================");
			queryToSolvers.getOrCreate(q).debugOutput();
			for (SootMethod m : queryToSolvers.get(q).getReachableMethods()) {
				logger.debug(m + "\n" + Joiner.on("\n\t").join(queryToSolvers.get(q).getResults(m).cellSet()));
			}
			queryToSolvers.getOrCreate(q).debugOutput();
			for (FieldReadPOI p : fieldReads.values()) {
				queryToSolvers.getOrCreate(q).debugFieldAutomaton(p.getStmt());
			}
			for (FieldWritePOI p : fieldWrites.values()) {
				queryToSolvers.getOrCreate(q).debugFieldAutomaton(p.getStmt());
				for (Statement succ : queryToSolvers.getOrCreate(q).getSuccsOf(p.getStmt())) {
					queryToSolvers.getOrCreate(q).debugFieldAutomaton(succ);
				}
			}
		}
	}

	public Debugger<W> getOrCreateDebugger() {
		if (this.debugger == null)
			this.debugger = createDebugger();
		return debugger;
	}

	public SimpleSeedFactory getSeedFactory() {
		return null;
	}

	public IBoomerangStats<W> getStats() {
		return stats;
	}

	public void onCreateSubSolver(AbstractBoomerangSolver<W> solver) {
		for (SolverCreationListener<W> l : solverCreationListeners) {
			l.onCreatedSolver(solver);
		}
	}

	public void registerSolverCreationListener(SolverCreationListener<W> l) {
		solverCreationListeners.add(l);
	}

	public Table<Statement, Val, W> getResults(Query seed) {
		final Table<Statement, Val, W> results = HashBasedTable.create();
		WeightedPAutomaton<Statement, INode<Val>, W> fieldAut = queryToSolvers.getOrCreate(seed).getCallAutomaton();
		for (Entry<Transition<Statement, INode<Val>>, W> e : fieldAut.getTransitionsToFinalWeights().entrySet()) {
			Transition<Statement, INode<Val>> t = e.getKey();
			W w = e.getValue();
			if (t.getLabel().equals(Statement.epsilon()))
				continue;
			if (t.getStart().fact().value() instanceof Local
					&& !t.getLabel().getMethod().equals(t.getStart().fact().m()))
				continue;
			if (t.getLabel().getUnit().isPresent())
				results.put(t.getLabel(), t.getStart().fact(), w);
		}
		return results;
	}

	public BoomerangOptions getOptions() {
		return this.options;
	}

}
