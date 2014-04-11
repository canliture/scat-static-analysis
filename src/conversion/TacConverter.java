package conversion;

import java.security.KeyStore.Builder;
import java.util.*;
import java.io.*;
import phpParser.*;
import pixy.MyOptions;
import pixy.Utils;
import analysis.inter.CallGraph;
import analysis.type.Type;
import analysis.type.TypeAnalysis;
import conversion.nodes.*;

// IMPORTANT NOTE:
// always compare places with "equals", never use the == operator!
// reason: due to file inclusions, one and the same variable can
// be represented by two different variable objects in the corresponding
// CFG nodes; the final symbol table after inclusion contains only
// one of these objects

public class TacConverter {

	// the file from which the parse tree was constructed
	private File file;

	// the PHP parse tree
	private ParseTree phpParseTree;

	// counter for temporary variables
	private int tempId = 0;
	// for logging the maximum temporary id
	private int maxTempId = 0;
	// ID for this converter; necessary to prevent name clash of
	// temporaries between different converters (converted files)
	private int id;

	// various stacks
	private LinkedList<CfgNode> breakTargetStack;
	private LinkedList<CfgNode> continueTargetStack;
	private LinkedList<TacFunction> functionStack;
	private LinkedList<TacClass> classStack;
	//Mona Nashaat:
	private LinkedList<TacInterface> interfaceStack;
	private LinkedList<TacNamespace> nameSpaceStack;
	// LATER: it would be cleaner to keep all symbol tables 
	// in the enclosing program converter (now: only the superglobals
	// are kept at this higher level)

	// symbol table for special variables;
	private SymbolTable specialSymbolTable;

	// symbol table for superglobals;
	// note: superglobals are defined in ProgramConverter.java
	private SymbolTable superSymbolTable;

	// shortcut to the symbol table of the main function, contains global variables
	private SymbolTable mainSymbolTable;

	// table containing constants (constant are not local to the
	// function in which they are defined, but accessible from
	// everywhere)
	private ConstantsTable constantsTable;

	// places for predefined constants
	private final TacPlace lineCPlace;

	private final TacPlace functionCPlace;
	//Mona Nashaat:
	private final TacPlace dirCPlace;
	//Mona Nashaat:
	private final TacPlace methodCPlace;
	//Mona Nashaat:
	private final TacPlace namespaceCPlace;

	private final TacPlace classCPlace;

	// special places
	//
	// special void place (for functions with "void" return value)
	private final TacPlace voidPlace;
	//
	// empty array offset
	private final TacPlace emptyOffsetPlace;
	//
	// an object
	private final Variable objectPlace;
	//
	// object member variable
	private final Variable memberPlace;

	// CAUTION: function names are case-insensitive in PHP, so bar()
	// and BAR() refer to the same function; the converter achieves this
	// behavior by transforming function names to lower case;

	// user-defined functions, including the main function;
	// function name -> function
	private Map<String,TacFunction> userFunctions;

	// method name -> class name -> method object
	// NOTE: method names are suffixed with a special string 
	// (see InternalStrings) to keep them distinct from normal functions    
	private Map<String,Map<String,TacFunction>> userMethods;

	// class name -> class
	private Map<String,TacClass> userClasses;

	//Mona Nashaat:
	// Namespace name -> Namespace
	private Map<String,TacNamespace> userNameSpaces;

	//Mona Nashaat:
	// Inteface name -> interface
	private Map<String,TacInterface> userInterfaces;

	// shortcut to the main function
	private TacFunction mainFunction;

	// maps functions AND methods to a list of function calls (for backpatching),
	// except for those function calls for which backpatching is known to be hopeless
	private Map<TacFunction,List<CfgNodeCallPrep>> functionCalls;
	// maps functions AND methods to a list of method calls (for backpatching),
	// except for those method calls for which backpatching is known to be hopeless
	private Map<TacFunction,List<CfgNodeCallPrep>> methodCalls;

	// switch indicating whether special node markers (~_) should be
	// considered
	private boolean specialNodes;

	// Map hotspotId (Integer) -> CfgNodeHotspot
	// only used for JUnit tests
	private Map<Integer,CfgNodeHotspot> hotspots;

	// list of include nodes; note that this list is only valid until the
	// first inclusion operation is performed
	private List<CfgNodeInclude> includeNodes;



	// ********************************************************************************* 
	// CONSTRUCTORS ********************************************************************    
	// *********************************************************************************

	public TacConverter(ParseTree phpParseTree, boolean specialNodes, int id, 
			File file, ProgramConverter pcv) {

		this.id = id;
		this.file = file;

		this.phpParseTree = phpParseTree;
		this.breakTargetStack = new LinkedList<CfgNode>();
		this.continueTargetStack = new LinkedList<CfgNode>();
		this.functionStack = new LinkedList<TacFunction>();
		this.classStack = new LinkedList<TacClass>();
		this.interfaceStack = new LinkedList<TacInterface>(); 
		this.nameSpaceStack = new LinkedList<TacNamespace>(); 

		//this.functionCalls = new LinkedList<CfgNodeCallPrep>();
		this.functionCalls = new HashMap<TacFunction,List<CfgNodeCallPrep>>();
		//this.methodCalls = new LinkedList<CfgNodeCallPrep>();
		this.methodCalls = new HashMap<TacFunction,List<CfgNodeCallPrep>>();

		this.voidPlace = new Literal("_void");
		// populate the special symbol table
		this.specialSymbolTable = new SymbolTable("_special");
		this.emptyOffsetPlace = new Variable("_emptyOffset", this.specialSymbolTable);
		this.specialSymbolTable.add((Variable) this.emptyOffsetPlace);
		this.objectPlace = new Variable("_object", this.specialSymbolTable);
		this.specialSymbolTable.add((Variable) this.objectPlace);
		this.memberPlace = new Variable(InternalStrings.memberName, this.specialSymbolTable);
		this.memberPlace.setIsMember(true);
		this.specialSymbolTable.add(this.memberPlace);

		this.mainSymbolTable = null;

		this.userFunctions = new HashMap<String,TacFunction>();
		this.mainFunction = null;

		// initialize symbol table for superglobals
		this.superSymbolTable = pcv.getSuperSymbolTable();

		// special superglobals for tainted and untainted values
		// (used in the builtin functions file);
		// an earlier version used constants instead, but this was problematic
		// since constants have an implicit untainted array label
		this.addSuperGlobal("$_TAINTED");
		this.addSuperGlobal("$_UNTAINTED");

		// initialize constants table
		this.constantsTable = new ConstantsTable();

		Constant lineConstant = Constant.getInstance("__LINE__");
		Constant functionConstant = Constant.getInstance("__FUNCTION__");
		Constant classConstant = Constant.getInstance("__CLASS__");
		//Mona Nashaat:
		Constant namespaceConstant = Constant.getInstance("__NAMESPACE__");
		//Mona Nashaat:
		Constant methodConstant = Constant.getInstance("__METHOD__");
		//Mona Nashaat:
		Constant dirConstant = Constant.getInstance("__DIR__");

		// the special constants true, false and null are handled by
		// makeConstantPlace(), Constant.<FIELD> and Constant.getInstance(),
		// later by the RalpGraph constructor (which replaces the constants with 
		// their corresponding special literals)
		this.constantsTable.add(Constant.TRUE);
		this.constantsTable.add(Constant.FALSE);
		this.constantsTable.add(Constant.NULL);
		this.constantsTable.add(lineConstant);
		this.constantsTable.add(functionConstant);
		this.constantsTable.add(classConstant);

		//Mona Nashaat:
		this.constantsTable.add(namespaceConstant);
		//Mona Nashaat:
		this.constantsTable.add(methodConstant);
		//Mona Nashaat:
		this.constantsTable.add(dirConstant);

		this.lineCPlace = lineConstant;
		this.functionCPlace = functionConstant;
		this.classCPlace = classConstant;
		//Mona Nashaat:
		this.methodCPlace=methodConstant;
		//Mona Nashaat:
		this.namespaceCPlace=namespaceConstant;
		//Mona Nashaat:
		this.dirCPlace=dirConstant;

		this.specialNodes = specialNodes;

		// initialize hotspots map
		this.hotspots = new HashMap<Integer,CfgNodeHotspot>();

		this.includeNodes = new LinkedList<CfgNodeInclude>();

		this.userClasses = new HashMap<String,TacClass>();
		//Mona Nashaat:
		this.userNameSpaces = new HashMap<String,TacNamespace>();
		//Mona Nashaat:
		this.userInterfaces = new HashMap<String,TacInterface>();
		this.userMethods = new HashMap<String,Map<String,TacFunction>>();

	}

	// *********************************************************************************    
	// OTHER ***************************************************************************
	// *********************************************************************************

	// assigns functions to cfg nodes
	public void assignFunctions() {

		// for each function
		for (TacFunction function : this.userFunctions.values()) {            
			if (function == null) {
				throw new RuntimeException("SNH");
			}

			// handle normal function cfg
			Cfg cfg = function.getCfg();
			this.assignFunctionsHelper(cfg, function);

			// handle function default cfgs
			for (TacFormalParam param : function.getParams()) {
				if (param.hasDefault()) {
					Cfg defaultCfg = param.getDefaultCfg();
					this.assignFunctionsHelper(defaultCfg, function);
				}
			}
		}

		// for each method: repeat the same
		for (TacFunction function : this.getMethods()) {

			if (function == null) {
				throw new RuntimeException("SNH");
			}

			// handle normal function cfg
			Cfg cfg = function.getCfg();
			this.assignFunctionsHelper(cfg, function);

			// handle function default cfgs
			for (TacFormalParam param : function.getParams()) {
				if (param.hasDefault()) {
					Cfg defaultCfg = param.getDefaultCfg();
					this.assignFunctionsHelper(defaultCfg, function);
				}
			}
		}
	}

	//  ********************************************************************************

	private void assignFunctionsHelper(Cfg cfg, TacFunction function) {

		for (Iterator<CfgNode> iter = cfg.dfPreOrder().iterator(); iter.hasNext(); ) {

			CfgNode node = (CfgNode) iter.next();
			node.setEnclosingFunction(function);

			// enter basic block
			if (node instanceof CfgNodeBasicBlock) {
				CfgNodeBasicBlock bb = (CfgNodeBasicBlock) node;
				for (CfgNode contained : bb.getContainedNodes()) {
					contained.setEnclosingFunction(function);
				}
			}
		}
	}

	// createBasicBlocks ***************************************************************

	// note: for function default cfgs (for default parameters), no basic blocks
	// are created (because it would be useless); don't change this behavior, or
	// you will get into trouble in other places
	public void createBasicBlocks() {

		// which cfg nodes did we already visit?
		Set<CfgNode> visited = new HashSet<CfgNode>();

		// for each function cfg...
		for (Iterator<TacFunction> iter = this.userFunctions.values().iterator(); iter.hasNext(); ) {
			TacFunction function = (TacFunction) iter.next();
			Cfg cfg = function.getCfg();
			CfgNode head = cfg.getHead();
			visited.add(head);
			this.createBasicBlocksHelper(head.getSuccessor(0), visited);
		}
		// for each method cfg...
		for (TacFunction function : this.getMethods()) {
			Cfg cfg = function.getCfg();
			CfgNode head = cfg.getHead();
			visited.add(head);
			this.createBasicBlocksHelper(head.getSuccessor(0), visited);
		}
	}

	//  createBasicBlocksHelper ********************************************************

	// LATER: non-recursive implementation (stack is getting deep);
	// receives a node that might be the beginning of a basic block
	private void createBasicBlocksHelper(CfgNode cfgNode, Set<CfgNode> visited) {

		if (visited.contains(cfgNode)) {
			// we've already been here: don't go any further
			return;
		}
		visited.add(cfgNode);

		if (this.allowedInBasicBlock(cfgNode)) {
			// start basic block!

			// number of nodes contained in this new basic block
			int contained = 1;

			// edges that shall enter the basic block
			List<CfgEdge> inEdges = cfgNode.getInEdges();

			// the first node in the basic block
			CfgNode startNode = cfgNode;

			//System.out.println("start node: " + cfgNode.toString());

			// the basic block
			CfgNodeBasicBlock basicBlock = new CfgNodeBasicBlock(startNode);

			// all nodes that are allowed to be in a basic block
			// have 1 or no successors
			CfgNode succ = startNode.getSuccessor(0);
			CfgNode beforeSucc = startNode;
			while (succ != null && this.allowedInBasicBlock(succ) && !visited.contains(succ)) {
				if (succ.getPredecessors().size() > 1) {
					// if the successor node has more than 1 predecessors,
					// we must not continue with our basic block here

					/*
                    System.out.println("has preds!!!");
                    for (CfgNode xpre : succ.getPredecessors()) {
                        System.out.println(xpre);
                    }
					 */                    
					break;                    
				}
				visited.add(succ);
				basicBlock.addNode(succ);
				contained++;
				beforeSucc = succ;
				succ = succ.getSuccessor(0);
			}
			/*
            System.out.println("succ != null: " + (succ != null));
            System.out.println("allowed: " + allowedInBasicBlock(succ));
            System.out.println("!visited: " + !visited.contains(succ));
			 */

			// if this is not a one-element basic block...
			// (i.e., does not create single-node basic blocks)
			if (contained > 1) {

				startNode.clearInEdges();
				basicBlock.informEnclosedNodes();

				// embed basic block:
				// connect with predecessors
				for (Iterator<CfgEdge> iter = inEdges.iterator(); iter.hasNext();) {
					CfgEdge inEdge = (CfgEdge) iter.next();
					inEdge.setDest(basicBlock);
					basicBlock.addInEdge(inEdge);
				}
				// connect with successor
				beforeSucc.clearOutEdges();
				if (succ != null) {
					succ.removeInEdge(beforeSucc);
					CfgEdge blockToSucc = new CfgEdge(basicBlock, succ, CfgEdge.NORMAL_EDGE);
					basicBlock.setOutEdge(0, blockToSucc);
					succ.addInEdge(blockToSucc);
				} else {
					// do nothing
				}
			}

			if (succ != null) {
				// continue algorithm at successor node
				this.createBasicBlocksHelper(succ, visited);
			}

		} else {
			// try successors
			//Mona Nashaat
			if(cfgNode!=null){
				List<CfgNode> successors = cfgNode.getSuccessors();
				for (Iterator<CfgNode> iter = successors.iterator(); iter.hasNext();) {
					CfgNode successor = (CfgNode) iter.next();
					this.createBasicBlocksHelper(successor, visited);
				}
			}
		}
	}

	//  allowedInBasicBlock ************************************************************

	private boolean allowedInBasicBlock(CfgNode cfgNode) {        
		// EFF: use a field inside CfgNode* instead;
		// note: alias information must not change inside basic blocks

		if (cfgNode instanceof CfgNodeCallBuiltin) {
			// allow calls to builtin functions to appear inside basic blocks,
			// but only if these builtin functions are not used as sinks
			// in later analyses
			CfgNodeCallBuiltin cfgNodeBuiltin = (CfgNodeCallBuiltin) cfgNode;
			return !MyOptions.isSink(cfgNodeBuiltin.getFunctionName());
		} else if (cfgNode instanceof CfgNodeAssignSimple ||
				cfgNode instanceof CfgNodeAssignUnary ||
				cfgNode instanceof CfgNodeAssignBinary ||
				//cfgNode instanceof CfgNodeAssignArray ||
				//cfgNode instanceof CfgNodeAssignRef ||
				cfgNode instanceof CfgNodeDefine ||
				cfgNode instanceof CfgNodeEmptyTest ||
				//cfgNode instanceof CfgNodeGlobal ||
				cfgNode instanceof CfgNodeIsset ||
				cfgNode instanceof CfgNodeStatic
				//|| cfgNode instanceof CfgNodeUnset
				) {
			return true;
		}
		return false;
	}

	//  include ************************************************************************

	// includes the given converter at the specified include node;
	// includingFunction: the one that contains the include node
	public void include(TacConverter includedTac, CfgNodeInclude includeNode, TacFunction includingFunction) {

		// INLINE MAIN CFG *************************************

		// functions inside the included file
		Map<String,TacFunction> includedUserFunctions = includedTac.getUserFunctions();

		// retrieve main cfg that is to be included
		TacFunction includedMainFunc = includedUserFunctions.get(InternalStrings.mainFunctionName);

		this.inlineMainCfg(includedMainFunc, includeNode);

		// add function and method calls inside the included main function (for backpatching)
		this.addFunctionCalls(this.mainFunction, includedTac.getFunctionCalls(includedMainFunc));
		this.addMethodCalls(this.mainFunction, includedTac.getMethodCalls(includedMainFunc));


		// EXPAND SYMBOL TABLE **************************************

		// the symbol table of the function that the include node belongs to
		// has to be expanded with the contents of the symbol table of the
		// main function of the included file

		SymbolTable includedMainSymTab = includedMainFunc.getSymbolTable();
		SymbolTable includingSymTab = includingFunction.getSymbolTable();

		/*
        System.out.println("expanding symtab for function " + function.getName());
        System.out.println("original including symtab:");
        Dumper.dump(includingSymTab, function.getName());
        System.out.println("expanding with the following contents:");
        Dumper.dump(includedMainSymTab, "included main");
		 */

		includingSymTab.addAll(includedMainSymTab);
		includedMainSymTab = null;  // don't forget that you must trash it now

		/*
        System.out.println("resulting including symtab:");
        Dumper.dump(includingSymTab, function.getName());
		 */

		// ADD FUNCTIONS ********************************************

		for (TacFunction includedFunc : includedUserFunctions.values()) {

			if (includedFunc.isMain()) {
				// we have already dealt with the main function above
				continue;
			}

			String includedFuncName = includedFunc.getName();

			// does this function already exist here?
			TacFunction existingFunction = this.userFunctions.get(includedFuncName);
			if (existingFunction != null) {
				// only issue a warning if they are not from the same file
				if (!existingFunction.getFileName().equals(includedFunc.getFileName())) {
					System.out.println("\nWarning: Duplicate function definition due to include: " + includedFuncName);

					System.out.println("- tried: " + includedFunc.getLoc());
					System.out.println("- using: " + existingFunction.getLoc());
				}
				continue;
			}

			// System.out.println("adding function " + includedFuncName);
			this.userFunctions.put(includedFuncName, includedFunc);

			// add method and function calls inside this function (for backpatching)
			this.addFunctionCalls(includedFunc, includedTac.getFunctionCalls(includedFunc));
			this.addMethodCalls(includedFunc, includedTac.getMethodCalls(includedFunc));
		}

		// add the call nodes contained in the included main function to the
		// list of call nodes contained in the including function
		//includingFunction.addContainedCalls(includedMainFunc.getContainedCalls());

		// TacFunction.calledFrom field doesn't need to be updated here:
		// is performed by the call to CfgNodeCall.setFunction in TacConverter.backpatch 

		// ADD CLASSES / METHODS ************************************

		// methods inside the included file
		Map<String,Map<String,TacFunction>> includedUserMethods = includedTac.getUserMethods();

		for (Map.Entry<String,Map<String,TacFunction>> entry1 : includedUserMethods.entrySet()) {

			String includedMethodName = entry1.getKey();
			Map<String,TacFunction> class2Method = entry1.getValue();

			for (Map.Entry<String,TacFunction> entry2 : class2Method.entrySet()) {

				String className = entry2.getKey();
				TacFunction includedMethod = entry2.getValue();

				// try to add this method
				TacFunction existingMethod = this.addMethod(includedMethodName, className, includedMethod);

				// if there already exists such a method...
				if (existingMethod != null) {

					// only issue a warning if they are not from the same file;
					// reason: if they are from the same file, it probably means that
					// this file was included more than once;
					// another possibility is that there is a real duplicate method definition
					// in the one file, but then, a warning for this was already issued
					// during the conversion of this file
					if (!existingMethod.getFileName().equals(includedMethod.getFileName())) {
						System.out.println("\nWarning: Duplicate method definition due to include: " + includedMethodName);
						System.out.println("- found: " + includedMethod.getLoc());
						System.out.println("- using: " + existingMethod.getLoc());
					}

					continue;
				}

				// add method and function calls inside this method (for backpatching)
				this.addFunctionCalls(includedMethod, includedTac.getFunctionCalls(includedMethod));
				this.addMethodCalls(includedMethod, includedTac.getMethodCalls(includedMethod));

			}
		}

		// add class info
		for (Map.Entry<String,TacClass> entry : includedTac.userClasses.entrySet()) {
			String includedClassName = entry.getKey();
			TacClass includedClass = entry.getValue();
			TacClass existingClass = this.userClasses.get(includedClassName);
			if (existingClass == null) {
				this.userClasses.put(includedClassName, includedClass);
			} else {
				// see comment about methods above (analogous) 
				if (!existingClass.getFileName().equals(includedClass.getFileName())) {
					System.out.println("\nWarning: Duplicate class definition due to include: " + includedClassName);
					System.out.println("- found: " + includedClass.getLoc());
					System.out.println("- using: " + existingClass.getLoc());
				}
			}
		}


		// no need to add anything for the special symbol table


		// SUPERGLOBALS SYMBOLTABLE *********************
		// also contains return variables

		/*
        System.out.println("original supersymtab:");
        Dumper.dump(this.superSymbolTable, "supersymtab");
        System.out.println("included supersymtab:");
        Dumper.dump(includedTac.getSuperSymbolTable(), "supersymtab");
		 */

		// no need to do this any longer, since the superSymbolTable is now kept
		// in the program converter
		//this.superSymbolTable.addAll(includedTac.getSuperSymbolTable());

		/*
        System.out.println("new supersymtab:");
        Dumper.dump(this.superSymbolTable, "supersymtab");
		 */

		// CONSTANTS TABLE **********************

		/*
        System.out.println("original constants table:");
        Dumper.dump(this.constantsTable);
        System.out.println("included constants table:");
        Dumper.dump(includedTac.getConstantsTable());
		 */

		this.constantsTable.addAll(includedTac.getConstantsTable());

		/*
        System.out.println("new constants table:");
        Dumper.dump(this.constantsTable);
		 */

		// ADJUST INCLUDED INCLUDE NODES *********************

		List<CfgNodeInclude> includedIncludeNodes = includedTac.getIncludeNodes();
		for (Iterator<CfgNodeInclude> iter = includedIncludeNodes.iterator(); iter.hasNext();) {
			CfgNodeInclude includedIncludeNode = (CfgNodeInclude) iter.next();
			// System.out.println("included include node: " + includedIncludeNode.getIncludeMe());
			if (includedIncludeNode.getIncludeFunction().isMain()) {
				// System.out.println("lies inside the main function!");
				// System.out.println("re-adjusting to " + function.getName());
				includedIncludeNode.setIncludeFunction(includingFunction);
			}
		}

		// HOTSPOTS

		if (this.specialNodes) {
			this.hotspots.putAll(includedTac.hotspots);
		}
	}

	//  inlineMainCfg ******************************************************************

	// helper function for "include": inlines the main CFG
	private void inlineMainCfg(TacFunction includedMainFunc, CfgNodeInclude includeNode) {

		Cfg includedMainCfg = includedMainFunc.getCfg();

		// entry and exit nodes of the included file's main cfg
		CfgNodeEntry includedEntry = (CfgNodeEntry) includedMainCfg.getHead();
		CfgNodeExit includedExit = (CfgNodeExit) includedMainCfg.getTail();

		// node after the entry
		CfgNode afterEntry = includedEntry.getSuccessor(0);

		// if this main cfg consists only of entry and exit node:
		// simply remove the include node
		if (afterEntry instanceof CfgNodeExit) {
			this.removeCfgNode(includeNode);
		} else {

			CfgNodeIncludeStart includeStart = new CfgNodeIncludeStart(includeNode.getFile(), includeNode.getParseNode());
			CfgNodeIncludeEnd includeEnd = new CfgNodeIncludeEnd(includeStart);

			// edges entering the exit
			List<CfgEdge> beforeExitList = includedExit.getInEdges();

			// edges entering and leaving the "include" node in the including file
			List<CfgEdge> includeInEdges = includeNode.getInEdges();
			CfgEdge[] includeOutEdges = includeNode.getOutEdges();

			// node after the "include" node
			CfgNode afterInclude;
			try {
				afterInclude = includeOutEdges[0].getDest();
			} catch (NullPointerException e) {
				System.out.println(includeNode.getLoc());
				throw e;
			}

			// remove edges (from the nodes' point of view)
			afterInclude.removeInEdge(includeNode);
			afterEntry.removeInEdge(includedEntry);

			// redirect edges that enter the include node to includeStart
			for (Iterator<CfgEdge> iterator = includeInEdges.iterator(); iterator.hasNext();) {
				CfgEdge inEdge = (CfgEdge) iterator.next();
				inEdge.setDest(includeStart);
				includeStart.addInEdge(inEdge);
			}

			// connect includeStart with the node following the
			// included cfg's entry node
			connect(includeStart, afterEntry);

			// redirect edges that enter the included cfg's exit node to includeEnd
			for (Iterator<CfgEdge> iterator = beforeExitList.iterator(); iterator.hasNext();) {
				CfgEdge inEdge = (CfgEdge) iterator.next();
				inEdge.setDest(includeEnd);
				includeEnd.addInEdge(inEdge);
			}

			// connect includeEnd with the node following the include node
			connect(includeEnd, afterInclude);
		}

	}

	// resetId *************************************************************************

	// resets the global tempId to the given logId;
	// use this method because it maintains maxTempId
	private void resetId(int logId) {
		if (this.tempId > this.maxTempId) {
			this.maxTempId = tempId;
		}
		this.tempId = logId;
	}

	// convert() ***********************************************************************

	public void convert() {     
		this.start(this.phpParseTree.getRoot());        
		if (this.tempId > this.maxTempId) {
			this.maxTempId = tempId;
		}
	}

	//  ********************************************************************************

	public void assignReversePostOrder() {
		this.mainFunction.assignReversePostOrder();
	}

	// newTemp(TacFunction function) ***************************************************

	private Variable newTemp(TacFunction function) {        
		String varName = "_t" + this.tempId++ + "_" + this.id;
		SymbolTable symbolTable = function.getSymbolTable();

		// try to recycle existing temporary
		Variable variable = symbolTable.getVariable(varName);

		// if it doesn't exist: create new one
		if (variable == null) {
			variable = new Variable(varName, symbolTable, true);
			symbolTable.add(variable);
		}

		return variable;
	}

	// newTemp *************************************************************************

	private Variable newTemp() {
		//Mona Nashaat:
		//    	TacFunction retme= (TacFunction) this.functionStack.getLast();
		if(this.functionStack!=null){
			return this.newTemp((TacFunction) this.functionStack.getLast());
		}
		else
			return null;
	}

	// getAllFunctions *****************************************************************

	// returns all functions and methods
	public List<TacFunction> getAllFunctions() {
		List<TacFunction> retMe = new LinkedList<TacFunction>();
		retMe.addAll(this.userFunctions.values());
		retMe.addAll(this.getMethods());
		return retMe;
	}

	//  ********************************************************************************

	// returns all user-defined methods
	private Collection<TacFunction> getMethods() {
		List<TacFunction> retMe = new LinkedList<TacFunction>();
		for (Map<String, TacFunction> class2Method : this.userMethods.values()) {
			retMe.addAll(class2Method.values());
		}
		return retMe;
	}

	//  getSize ************************************************************************

	// returns the sum of the sizes of the contained cfg's
	public int getSize() {
		int size = 0;
		for (Iterator<TacFunction> iter = this.userFunctions.values().iterator(); iter.hasNext();) {
			TacFunction function = (TacFunction) iter.next();
			size += function.getCfg().size();
		}
		for (TacFunction function : this.getMethods()) {
			size += function.getCfg().size();
		}
		return size;
	}

	//  ********************************************************************************

	public File getFile() {
		return this.file;
	}

	// getUserFunctions ****************************************************************

	public Map<String,TacFunction> getUserFunctions() {
		return this.userFunctions;
	}

	// getUserMethods ******************************************************************

	public Map<String,Map<String,TacFunction>> getUserMethods() {
		return this.userMethods;
	}

	// hasEmptyMain ********************************************************************

	public boolean hasEmptyMain() {
		if (this.mainFunction.isEmpty()) {
			return true;
		} else {
			return false;
		}
	}

	// getSuperSymbolTable *************************************************************

	public SymbolTable getSuperSymbolTable() {
		return this.superSymbolTable;
	}

	// getSpecialSymbolTable ***********************************************************

	SymbolTable getSpecialSymbolTable() {
		return this.specialSymbolTable;
	}

	// getConstantsTable ***************************************************************

	public ConstantsTable getConstantsTable() {
		return this.constantsTable;
	}

	// getMaxTempId ********************************************************************

	public int getMaxTempId() {
		return this.maxTempId;
	}

	// getPlacesList *******************************************************************

	// returns a list containing all variables and constants
	public List<TacPlace> getPlacesList() {
		List<TacPlace> placesList = new LinkedList<TacPlace>();
		placesList.addAll(this.constantsTable.getConstants().values());
		placesList.addAll(this.getVariablesList());
		placesList.addAll(this.superSymbolTable.getVariables().values());
		placesList.addAll(this.specialSymbolTable.getVariables().values());
		return placesList;
	}

	//  getNumberOfVariables ***********************************************************

	// returns the number of variables used by the programmer (i.e. those variables 
	// that are explicitly mentioned in the source code)
	public int getNumberOfVariables() {

		int varNum = 0;
		List<Variable> varList = this.getVariablesList();
		for (Iterator<Variable> iter = varList.iterator(); iter.hasNext();) {
			Variable var = (Variable) iter.next();
			if (var.isTemp()) {
				continue;
			}
			// System.out.println(var.toString());
			varNum++;
		}

		return varNum;
	}

	// stats ***************************************************************************

	// prints statistical information
	public void stats() {

		// global variables
		int globalVarsReal = 0;
		int globalVarsTemp = 0;
		for (Variable globalVar : this.mainSymbolTable.getVariablesColl()) {
			if (globalVar.isTemp()) {
				globalVarsTemp++;
			} else {
				globalVarsReal++;
			}
			//System.out.print(globalVar.getName() + ", ");
		}

		if (MyOptions.optionV) {
			System.out.println("global variables (real): " + globalVarsReal);
			System.out.println("global variables (temp): " + globalVarsTemp);
		}

		int tempsTotal = globalVarsTemp;
		int gShadowsTotal = 0;
		int fShadowsTotal = 0;
		int normalLocalsTotal = 0;

		for (TacFunction userFunction : this.userFunctions.values()) {
			if (userFunction.isMain()) {
				// the stats for the main function were already extracted above 
				continue;
			}
			Collection<Variable> vars = userFunction.getSymbolTable().getVariablesColl();

			int temps = 0;
			for (Variable var : vars) {
				if (var.isTemp()) {
					temps++;
				}
			}
			int gShadows = userFunction.getSymbolTable().getGlobals2GShadows().size();
			int fShadows = userFunction.getSymbolTable().getFormals2FShadows().size();
			int normalLocals = vars.size() - temps - gShadows - fShadows;

			tempsTotal += temps;
			gShadowsTotal += gShadows;
			fShadowsTotal += fShadows;
			normalLocalsTotal += normalLocals;
			int a=1;
			if (a==2) {
				//if (false) {
				System.out.println("_______________");
				System.out.println(userFunction.getName() + ": " + vars.size() + " variables");
				System.out.println("Normal Locals: " + normalLocals);
				System.out.println("Temps: " + temps);
				System.out.println("G-Shadows: " + gShadows);
				System.out.println("F-Shadows: " + fShadows);
			}
		}

		if (MyOptions.optionV) {
			System.out.println();
			System.out.println("Functions:     " + (this.userFunctions.size() - 1));
			System.out.println("Normal Locals: " + normalLocalsTotal);
			System.out.println("Temps:         " + tempsTotal);
			System.out.println("G-Shadows:     " + gShadowsTotal);
			System.out.println("F-Shadows:     " + fShadowsTotal);
			System.out.println();
			System.out.println("Constants Table size: " + this.constantsTable.size());
			System.out.println("SuperSymTab size:     " + this.superSymbolTable.size());
			System.out.println("Special SymTab size:  " + this.specialSymbolTable.size());
			System.out.println();
			System.out.println("Classes: " + this.userClasses.size());
		}

	}

	// getMemberPlace ******************************************************************

	public Variable getMemberPlace() {
		return this.memberPlace;
	}

	//  getMainFunction ****************************************************************

	public TacFunction getMainFunction() {
		return this.mainFunction;
	}

	//  getHotspot *********************************************************************

	// returns null if the given hotspot doesn't exist
	public CfgNodeHotspot getHotspot(int hotspotId) {
		return (CfgNodeHotspot) this.hotspots.get(new Integer(hotspotId));
	}

	//  addHotspot *********************************************************************

	void addHotspot(CfgNodeHotspot node) {
		this.hotspots.put(node.getHotspotId(), node);
	}

	public void addIncludeNode(CfgNodeInclude node) {
		this.includeNodes.add(node);
	}

	// getVariablesList ****************************************************************

	// returns a list containing all variables
	List<Variable> getVariablesList() {
		List<Variable> variablesList = new LinkedList<Variable>();
		for (Iterator<TacFunction> iter = this.userFunctions.values().iterator(); iter.hasNext(); ) {
			TacFunction function = (TacFunction) iter.next();
			variablesList.addAll(function.getSymbolTable().getVariables().values());
		}
		for (TacFunction function : this.getMethods()) {
			variablesList.addAll(function.getSymbolTable().getVariables().values());
		}
		return variablesList;
	}

	//  getVariable ********************************************************************

	// returns the variable with the given name that is local to the 
	// given function or method; also tries to find it in the superglobals
	// symbol table returns null it doesn't exist;
	public Variable getVariable(TacFunction fm, String varName) {
		Variable var = fm.getVariable(varName);
		if (var == null) {
			var = this.superSymbolTable.getVariable(varName);
		}
		return var;

	}

	//  getVariable ********************************************************************

	// returns the variable with the given name that is local to the 
	// given function (NOT method); throws an exception if it doesn't exist;
	// only used by testcases
	public Variable getFuncVariable(String functionName, String varName) {
		TacFunction function = this.userFunctions.get(functionName);
		Variable retMe = function.getVariable(varName);
		/*
        if (retMe == null) {
            throw new RuntimeException("Variable " + varName + " in function " + 
                    functionName + " does not exist");
        }*/
		return retMe;
	}

	//  getMethodVariable **************************************************************

	// returns the variable with the given name that is local to the 
	// given method (NOT function); throws an exception if it doesn't exist;
	// only used by testcases
	public Variable getMethodVariable(String functionName, String varName) {
		Map<String,TacFunction> class2Method = this.userMethods.get(functionName);
		if (class2Method == null || class2Method.size() != 1) {
			throw new RuntimeException("Method " + 
					functionName + " either does not exist or has duplicates");
		}
		TacFunction method = class2Method.values().iterator().next();
		Variable retMe = method.getVariable(varName);
		if (retMe == null) {
			throw new RuntimeException("Variable " + varName + " in function " + 
					functionName + " does not exist");
		}
		return retMe;
	}

	//  getConstant ********************************************************************

	// returns the constant with the given name; 
	// throws an exception if it doesn't exist
	public Constant getConstant(String constName) {
		Constant retMe = this.constantsTable.getConstant(constName);
		if (retMe == null) {
			throw new RuntimeException("Constant " + constName + " does not exist");
		}
		return retMe;
	}

	//  getConstantGraceful ************************************************************

	// returns the constant with the given name; 
	// throws an exception if it doesn't exist
	public Constant getConstantGraceful(String constName) {
		Constant retMe = this.constantsTable.getConstant(constName);
		return retMe;
	}

	//  getSuperGlobal *****************************************************************

	// returns the superglobal variable with the given name
	public Variable getSuperGlobal(String varName) {
		return this.superSymbolTable.getVariable(varName);
	}

	//  getIncludeNodes () *************************************************************

	public List<CfgNodeInclude> getIncludeNodes() {
		return this.includeNodes;
	}

	//  ********************************************************************************

	public Map<String, TacClass> getUserClasses() {
		return this.userClasses;
	}

	// optimize(Cfg) *******************************************************************    

	// removes empty nodes from a cfg;
	// the cfg must have at least one non-empty node;
	private void optimize(Cfg cfg) {

		CfgNode startHere;

		// remove leading empty nodes
		// (requires "head" adjustment)
		for (startHere = cfg.getHead(); startHere instanceof CfgNodeEmpty; ) {
			startHere = removeCfgNode(startHere);
		}
		cfg.setHead(startHere);

		// remove remaining empty nodes
		Iterator<CfgNode> iter = cfg.dfPreOrder().iterator();
		while (iter.hasNext()) {
			CfgNode current = (CfgNode) iter.next();
			if (current instanceof CfgNodeEmpty) {
				this.removeCfgNode(current);
			}
		}
	}

	// removeCfgNode *******************************************************************

	// removes empty cfg node and returns successor
	private CfgNode removeCfgNode(CfgNode cfgNode) {

		// empty nodes have at most one successor:
		CfgEdge outEdge = cfgNode.getOutEdge(0);
		List<CfgEdge> inEdges = cfgNode.getInEdges();
		if (outEdge != null) {
			CfgNode succ = outEdge.getDest();

			// remove this edge (from the viewpoint of the successor)
			succ.removeInEdge(cfgNode);

			// redirect all incoming nodes to this successor node:
			for (Iterator<CfgEdge> iter = inEdges.iterator(); iter.hasNext(); ) {
				CfgEdge inEdge = (CfgEdge) iter.next();
				inEdge.setDest(succ);
				succ.addInEdge(inEdge);
			}
			return succ;
		} else {

			// this node is a dead end: don't do anything;
			// why you should not remove it: might be the first
			// statement after a branch, would screw up the CFG
			/*
            for (Iterator iter = inEdges.iterator(); iter.hasNext(); ) {
                iter.next();
                iter.remove();
            }
			 */
			return null;
		}
	}

	// old version: recursive (stack overflow)

	/*
    private void optimize(Cfg cfg) {
        CfgNode startHere;
        Set<CfgNode> visited = new HashSet<CfgNode>();
        // remove leading empty nodes
        for (startHere = cfg.getHead(); startHere instanceof CfgNodeEmpty; ) {
            startHere = removeCfgNode(startHere);
        }
        cfg.setHead(startHere);

        this.optimize(startHere, visited);
    }

    private void optimize(CfgNode cfgNode, Set<CfgNode> visited) {

        if (cfgNode instanceof CfgNodeEmpty) {
            this.removeCfgNode(cfgNode);
        }

        // mark this node as visited
        visited.add(cfgNode);

        // handle successors
        for (int i = 0; i < 2; i++) {
            CfgEdge outEdge = cfgNode.getOutEdge(i);
            if (outEdge != null) {
                CfgNode succ = outEdge.getDest(); 
                if (!visited.contains(succ)) {
                    optimize(succ, visited);
                }
            }
        }
    }
	 */

	//  transformGlobals(Cfg) **********************************************************

	// replaces literal entries of the $GLOBALS array in the given CFG with
	// the corresponding global variables (i.e., with local variables of the
	// main function)
	private void transformGlobals(Cfg cfg) {

		Variable globalsArray = this.superSymbolTable.getVariable("$GLOBALS");

		// traverse CFG...
		for (Iterator<CfgNode> iter = cfg.dfPreOrder().iterator(); iter.hasNext(); ) {

			CfgNode cfgNode = (CfgNode) iter.next();

			// inspect this node's variables...
			int varCount = -1;
			for (Iterator<Variable> varIter = cfgNode.getVariables().iterator(); varIter.hasNext(); ) {
				Variable var = (Variable) varIter.next();
				varCount ++;

				// nothing to do for placeholders
				if (var == null) {
					continue;
				}

				// nothing to do for non-array-elements
				if (!var.isArrayElement()) {
					continue;
				}

				// nothing to do for array elements whose top enclosing array
				// is not the $GLOBALS array
				if (!(var.getTopEnclosingArray().equals(globalsArray))) {
					continue;
				}

				// at this point, we can't do anything for elements with
				// non-literal indices 
				if (var.hasNonLiteralIndices()) {
					continue;
				}

				// construct name of the corresponding main local
				StringBuilder varNameBuffer = new StringBuilder(); 
				varNameBuffer.append("$");
				Iterator<TacPlace> indicesIter = var.getIndices().iterator();
				TacPlace firstIndex = (TacPlace) indicesIter.next();
				varNameBuffer.append(firstIndex.getLiteral().toString());
				while (indicesIter.hasNext()) {
					TacPlace index = (TacPlace) indicesIter.next();
					varNameBuffer.append("[");
					varNameBuffer.append(index.getLiteral().toString());
					varNameBuffer.append("]");
				}
				String varName = varNameBuffer.toString();

				// retrieve/create the corresponding variable and use it as replacement
				Variable transformedVar = (Variable) this.makePlace(varName, this.mainSymbolTable);
				cfgNode.replaceVariable(varCount, transformedVar);
			}
		}

	}

	//  ********************************************************************************

	// scans the program for "global" statements; replaces local function variables
	// with global variables accordingly; e.g., if it finds the statement 
	// "global $x" in function foo, it replaces all occurrences of the local variable
	// $x in foo with the global variable $x; note that this is not correct in
	// all cases (not flow-sensitive), but it provides good results in practice;
	// this should only be used if a full-fledged alias analysis is not desired
	void replaceGlobals() {

		// for each user-defined function... 
		for (Iterator<TacFunction> funcIter = this.userFunctions.values().iterator(); funcIter.hasNext();) {

			TacFunction userFunction = (TacFunction) funcIter.next();
			if (userFunction.isMain()) {
				// nothing to do for the main function
				continue;
			}



			// info retrieval *******

			// retrieve a set of variables that have been declared as "global";
			// mapping "variable name" -> "global variable"
			Map<String,Variable> declaredAsGlobal = new HashMap<String,Variable>();
			// traverse CFG...
			for (Iterator<CfgNode> cfgIter = userFunction.getCfg().dfPreOrder().iterator(); cfgIter.hasNext(); ) {
				CfgNode cfgNodeX = (CfgNode) cfgIter.next();
				if (!(cfgNodeX instanceof CfgNodeGlobal)) {
					// we are only interested in "global" statements
					continue;
				}
				CfgNodeGlobal cfgNode = (CfgNodeGlobal) cfgNodeX;
				String varName = cfgNode.getOperand().getName();
				Variable correspondingGlobal = this.mainSymbolTable.getVariable(varName);

				if (correspondingGlobal == null) {
					if (this.superSymbolTable.getVariable(varName) != null) {
						// this is bad programming practice: the programmer has
						// declared a superglobal as "global"; nothing to do here
					} else {
						// this means that there is a "global" declaration, but
						// the corresponding global variable has not been created;
						// happens here, for example:
						// global ${$foo['bar']}
						// reason for this strange syntax: there doesn't seem to 
						// be an easier way to declare array elements as global;

						// simply ignore for now

						//System.out.println(cfgNode.getLoc());
						//throw new RuntimeException("SNH: " + varName);
					}
				} else {
					declaredAsGlobal.put(varName, correspondingGlobal);
				}
			}



			// replacement *******

			// traverse CFG...
			for (Iterator<CfgNode> cfgIter = userFunction.getCfg().dfPreOrder().iterator(); cfgIter.hasNext(); ) {
				CfgNode cfgNode = (CfgNode) cfgIter.next();

				// nothing to do for "global" statements
				if (cfgNode instanceof CfgNodeGlobal) {
					continue;
				}

				// inspect this node's variables...
				int varCount = -1;
				for (Iterator<Variable> varIter = cfgNode.getVariables().iterator(); varIter.hasNext(); ) {
					Variable var = (Variable) varIter.next();
					varCount++;

					// nothing to do for placeholders
					if (var == null) {
						continue;
					}

					// if the name of the inspected variable matches the name of
					// one of the variables that have been declared "global":
					// raplace
					Variable theGlobal = declaredAsGlobal.get(var.getName());
					if (theGlobal != null) {
						//System.out.println("at: " + cfgNode.getClass() + ", " + cfgNode.getOrigLineno());
						//System.out.println("replacing: " + var);
						//System.out.println("with: " + theGlobal);
						cfgNode.replaceVariable(varCount, theGlobal);
					}

					// TODO: also consider the case that the inspected variable is
					// an array element of some variable that has been declared
					// "global"; also see above!
				}
			}
		}
	}

	// makePlace(String, SymbolTable) **************************************************

	private Variable makePlace(String varName, SymbolTable symbolTable) {

		// System.out.println("called makePlace with: " + varName);

		// lookup variable in given symbol table
		Variable variable = symbolTable.getVariable(varName);

		// if it isn't there: lookup variable in superglobals symbol table
		if (variable == null) {
			variable = this.superSymbolTable.getVariable(varName);
		}

		// if it isn't there either: add it to the given symbol table
		if (variable == null) {
			variable = new Variable(varName, symbolTable);
			symbolTable.add(variable);
		}

		// if it is created for the superSymbolTable, it has to be a superglobal
		if (symbolTable == this.superSymbolTable) {
			variable.setIsSuperGlobal(true);
		}

		// return place for the variable
		return variable;
	}

	// makePlace(String) ***************************************************************

	// convenience wrapper around makePlace(String, SymbolTable)
	private Variable makePlace(String varName) {
		return this.makePlace(varName, 
				(SymbolTable) ((TacFunction) this.functionStack.getLast()).getSymbolTable());
	}

	// makeReturnPlace(String) *********************************************************

	// returns the place for the given function's return variable;
	// return variables are named 
	// "InternalStrings.returnPrefix<function name>"
	private Variable makeReturnPlace(String functionName) {
		Variable returnPlace = this.makePlace(InternalStrings.returnPrefix + functionName, this.superSymbolTable);
		((Variable) returnPlace).setIsReturnVariable(true);
		return returnPlace;
	}

	// makeConstantPlace ***************************************************************

	private TacPlace makeConstantPlace(String label) {

		// lookup constant
		Constant constant = this.constantsTable.getConstant(label);

		// if it isn't there: add it to the constants table;
		// case-insensitive variants of true and false are handled in
		// the following call to getInstance()
		if (constant == null) {
			constant = Constant.getInstance(label);
			this.constantsTable.add(constant);
		}
		// return place for the constant
		return constant;
	}

	// addSuperGlobal ******************************************************************

	private void addSuperGlobal(String varName) {
		TacPlace sgPlace = this.makePlace(varName, this.superSymbolTable);
		Variable var = sgPlace.getVariable();
		var.setIsSuperGlobal(true);
	}

	// connect(CfgNode, CfgNode, int) **************************************************    

	private static void connect(CfgNode source, CfgNode dest, int edgeType) {
		if (edgeType != CfgEdge.NO_EDGE) {

			// create edge
			CfgEdge edge = new CfgEdge(source, dest, edgeType);

			// add outgoing edge to source node
			if (edgeType == CfgEdge.TRUE_EDGE) {
				source.setOutEdge(1, edge);
			} else {
				source.setOutEdge(0, edge);
			}

			// add incoming edge to destination node
			dest.addInEdge(edge);

		}
	}

	// connect(CfgNode, CfgNode) **************************************************    

	static void connect(CfgNode source, CfgNode dest) {
		connect(source, dest, CfgEdge.NORMAL_EDGE);
	}

	// connect(Cfg, Cfg) ***************************************************************    

	static void connect(Cfg firstCfg, Cfg secondCfg) {
		connect(
				firstCfg.getTail(), 
				secondCfg.getHead(), 
				firstCfg.getTailEdgeType());
	}

	// connect(Cfg, CfgNode) ***********************************************************    

	static void connect(Cfg firstCfg, CfgNode dest) {
		connect(
				firstCfg.getTail(), 
				dest, 
				firstCfg.getTailEdgeType());
	}

	// connect(CfgNode, Cfg, int) ******************************************************

	private static void connect(CfgNode source, Cfg secondCfg, int edgeType) {
		connect(
				source,
				secondCfg.getHead(),
				edgeType);
	}

	// connect(CfgNode, Cfg) ******************************************************

	static void connect(CfgNode source, Cfg secondCfg) {
		connect(source, secondCfg, CfgEdge.NORMAL_EDGE);
	}

	//  ********************************************************************************

	// adds a method to this.userMethods; if a method with this methodName and
	// className already exists, it returns the already existing method;
	// otherwise, it adds the method and returns null
	private TacFunction addMethod(String methodName, String className, TacFunction method) {
		/*
        System.out.println("adding method:");
        System.out.println("- methodName: " + methodName);
        System.out.println("- method: " + method);
        System.out.println("- className: " + className);
		 */
		Map<String, TacFunction> class2Method = this.userMethods.get(methodName);
		if (class2Method == null) {
			class2Method = new HashMap<String,TacFunction>();
			this.userMethods.put(methodName, class2Method);
		}
		TacFunction existingMethod = class2Method.get(className);
		if (existingMethod != null) {
			return existingMethod;
		} else {
			class2Method.put(className, method);
			return null;
		}
	}

	//  ********************************************************************************

	// returns the method with the given name in the given class; null if
	// there is no such method
	private TacFunction getMethod(String methodName, String className) {
		Map<String, TacFunction> class2Method = this.userMethods.get(methodName);
		if (class2Method == null) {
			// no such method name
			return null;
		}
		return class2Method.get(className);
	}



	// *********************************************************************************    
	// RULE HELPERS ********************************************************************
	// *********************************************************************************

	/*
	 * The following methods are closely related to the "Parse Rule Methods" below and
	 * have been "outsourced" to prevent code redundancy.
	 *
	 */

	// booleanHelper *******************************************************************

	// handles short-circuit code for AND, &&, OR, ||
	void booleanHelper(ParseNode node, TacAttributes myAtts, int type) {

		// short-circuit is taken into account...
		//
		// don't forget that the logical operators ("and", "or") have a very
		// low priority:
		// "$c =  $a and $b " is different from
		// "$c =  $a &&  $b " is equivalent to
		// "$c = ($a and $b)"

		Variable myPlace = newTemp();

		TacAttributes atts0 = this.expr(node.getChild(0));
		TacAttributes atts2 = this.expr(node.getChild(2));

		// nodes for assigning true or false to the temporary
		CfgNode trueNode = new CfgNodeAssignSimple(myPlace, Constant.TRUE, node);
		CfgNode falseNode = new CfgNodeAssignSimple(myPlace, Constant.FALSE, node);

		// target node for trueNode and falseNode
		CfgNode emptyNode = new CfgNodeEmpty();
		connect(trueNode, emptyNode);
		connect(falseNode, emptyNode);

		// test for first expression
		CfgNode ifNode0 = new CfgNodeIf(
				atts0.getPlace(), 
				Constant.TRUE, 
				TacOperators.IS_EQUAL,
				node.getChild(0));

		// test for second expression
		CfgNode ifNode2 = new CfgNodeIf(
				atts2.getPlace(), 
				Constant.TRUE, 
				TacOperators.IS_EQUAL,
				node.getChild(2));

		// expr0's code comes before its test
		connect(atts0.getCfg(), ifNode0);

		// expr2's code comes before the test
		connect(atts2.getCfg(), ifNode2);


		if (type == PhpSymbols.T_LOGICAL_OR) {
			// OR, ||

			// if first test succeeds: done, true!
			connect(ifNode0, trueNode, CfgEdge.TRUE_EDGE);

			// if first test doesn't succeed: evaluate expr2
			connect(ifNode0, atts2.getCfg(), CfgEdge.FALSE_EDGE);

			// if second test succeeds: done, true!
			connect(ifNode2, trueNode, CfgEdge.TRUE_EDGE);

			// if second test doesn't succeed: done, false!
			connect(ifNode2, falseNode, CfgEdge.FALSE_EDGE);

		} else {
			// AND, &&

			// if test succeeds: evaluate expr2
			connect(ifNode0, atts2.getCfg(), CfgEdge.TRUE_EDGE);

			// if test doesn't succeed: done, false!
			connect(ifNode0, falseNode, CfgEdge.FALSE_EDGE);

			// if test succeeds: done, true!
			connect(ifNode2, trueNode, CfgEdge.TRUE_EDGE);

			// if test doesn't succeed: done, false!
			connect(ifNode2, falseNode, CfgEdge.FALSE_EDGE);

		}

		myAtts.setCfg(new Cfg(
				atts0.getCfg().getHead(),
				emptyNode));
		myAtts.setPlace(myPlace);
	}

	// expOpExp ************************************************************************

	// expression operator expression
	void expOpExp(ParseNode node, int op, TacAttributes myAtts) {

		Variable myPlace = null;
		int logId = this.tempId;

		TacAttributes atts0 = this.expr(node.getChild(0));
		// if the first expression has been stored to a temporary variable,
		// this variable can be reused
		if (atts0.getPlace().isVariable() && ((Variable) atts0.getPlace()).isTemp()) {
			myPlace = (Variable) atts0.getPlace();
			// update logId so that the next call to resetId will
			// leave tempId higher than myPlace's
			logId = this.tempId;
		} else {
			this.resetId(logId);
		}
		TacAttributes atts2=null;
		if(op==29){
			atts2 = this.class_name_reference(node.getChild(2));
		}
		else{
			atts2 = this.expr(node.getChild(2));
		}
		// if we are still trying to reuse
		if (myPlace == null) {
			if(atts2.getPlace()!=null){
				if (atts2.getPlace().isVariable() && ((Variable) atts2.getPlace()).isTemp()) {
					myPlace = (Variable) atts2.getPlace();
				} else {
					this.resetId(logId);
					myPlace = this.newTemp();
				}
			}
			else{
				this.resetId(logId);
			}
		} else {
			this.resetId(logId);
		}

		CfgNode cfgNode = new CfgNodeAssignBinary(
				myPlace, atts0.getPlace(), atts2.getPlace(), op, node);
		connect(atts0.getCfg(), atts2.getCfg());
		connect(atts2.getCfg(), cfgNode);

		myAtts.setCfg(new Cfg(atts0.getCfg().getHead(), cfgNode));
		myAtts.setPlace(myPlace);

	}

	// cvarOpExp ***********************************************************************

	//Mona Nashaat:change cvar into variable
	// variable operator expression
	void cvarOpExp(ParseNode node, int op, TacAttributes myAtts) {

		TacAttributes atts0 = this.variable(node.getChild(0));
		TacAttributes atts2 = this.expr(node.getChild(2));

		CfgNode cfgNode = new CfgNodeAssignBinary(
				(Variable) atts0.getPlace(), atts0.getPlace(), atts2.getPlace(), op, node);
		connect(atts0.getCfg(), atts2.getCfg());
		connect(atts2.getCfg(), cfgNode);

		myAtts.setCfg(new Cfg(atts0.getCfg().getHead(), cfgNode));
		myAtts.setPlace(atts0.getPlace());
	}

	// postIncDec **********************************************************************

	//Mona Nashaat:change rw_cvar into variable
	// post-increment and post-decrement
	void postIncDec(ParseNode node, int op, TacAttributes myAtts) {

		// temporary to rescue the variable's old value;
		// will be the expression's place
		Variable tempPlace = newTemp();
		//Mona Nashaat:change rw_cvar into variable
		TacAttributes atts0 = this.variable(node.getChild(0));
		TacPlace addMePlace = new Literal("1");

		// assign the variable's old value to the temporary
		CfgNode rescueNode = new CfgNodeAssignSimple(
				tempPlace, atts0.getPlace(), node.getChild(0));

		// increment / decrement the variable
		CfgNode cfgNode = new CfgNodeAssignBinary(
				(Variable) atts0.getPlace(), atts0.getPlace(), addMePlace, op, node);

		connect(atts0.getCfg(), rescueNode);
		connect(rescueNode, cfgNode);

		myAtts.setCfg(new Cfg(atts0.getCfg().getHead(), cfgNode));
		myAtts.setPlace(tempPlace);
	}
	// Clone **********************************************************************

	//Mona Nashaat:change rw_cvar into variable
	// Clone Objects
	void Clone(ParseNode Parent, ParseNode node, TacAttributes myAtts) {

		//Mona Nashaat:change rw_cvar into variable
		TacAttributes atts0 = this.variable(Parent);
		TacAttributes atts1 = this.expr(node.getChild(1));		

		connect(atts0.getCfg(), atts1.getCfg());

		myAtts.setCfg(atts0.getCfg());
		myAtts.setPlace(atts0.getPlace());
	}


	// preIncDec **********************************************************************

	//Mona Nashaat:change rw_cvar into variable
	// pre-increment and pre-decrement
	void preIncDec(ParseNode node, int op, TacAttributes myAtts) {

		//Mona Nashaat:change rw_cvar into variable
		TacAttributes atts1 = this.variable(node.getChild(1));
		TacPlace addMePlace = new Literal("1");

		CfgNode cfgNode = new CfgNodeAssignBinary(
				(Variable) atts1.getPlace(), atts1.getPlace(), addMePlace, op, node);

		connect(atts1.getCfg(), cfgNode);

		myAtts.setCfg(new Cfg(atts1.getCfg().getHead(), cfgNode));
		myAtts.setPlace(atts1.getPlace());
	}

	// opExp ***************************************************************************

	// operator expression
	void opExp(ParseNode node, int op, TacAttributes myAtts) {

		Variable tempPlace = newTemp();
		TacAttributes atts1 = this.expr(node.getChild(1));

		CfgNode cfgNode = new CfgNodeAssignUnary(
				tempPlace, atts1.getPlace(), op, node);
		connect(atts1.getCfg(), cfgNode);

		myAtts.setCfg(new Cfg(atts1.getCfg().getHead(), cfgNode));
		myAtts.setPlace(tempPlace);
	}

	// functionHelper ******************************************************************    

	void functionHelper(ParseNode node, int paramListNum, int statNum, TacAttributes myAtts) {

		// LATER
		// - case: no return statement inside the function, but a caller uses
		//   the function's return value: probably a bug
		// - real-world effect in this case: the function returns the special 
		//   value "NULL" (can be checked via var_dump())
		// - current implementation: the return value is TOP
		// - alternative, more exact implementation: assign the special value NULL
		//   to the functions return variable at the beginning of the function

		// name and referencedom
		String functionName="";
		if(node.getChild(2).getSymbol()==PhpSymbols.T_STRING)	{
			functionName = node.getChild(2).getLexeme().toLowerCase();
		}
		boolean isReference = 
				(node.getChild(1).getChild(0).getSymbol() == PhpSymbols.T_EPSILON) ? false : true;

		TacFunction existingFunction = this.userFunctions.get(functionName);
		if (existingFunction != null) {

			// either a bug or a conditional function declaration
			// approx: simply ignore this additional declaration
			System.out.println("\nWarning: Duplicate function definition: " + functionName);
			//  System.out.println("- found: " + node.getLoc());
			//System.out.println("- using: " + existingFunction.getLoc());

			// return empty Cfg (we don't put function Cfgs inside one another)
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			return;
		}

		// the function's frame Cfg
		CfgNodeEntry entryNode = new CfgNodeEntry(node);
		CfgNodeExit exitNode = new CfgNodeExit(node);
		Cfg cfg = new Cfg(entryNode, exitNode, CfgEdge.NO_EDGE);

		// create function object
		TacFunction function = new TacFunction(
				functionName,
				cfg,
				this.makeReturnPlace(functionName),
				isReference,
				node, "");
		this.userFunctions.put(functionName, function);

		// push the function's name onto the function stack
		this.functionStack.add(function);

		// construct parameter list
		TacAttributes attsParamList = this.parameter_list(node.getChild(paramListNum));

		// for all formal parameters:
		// if this param has a default cfg, inform all nodes of this default cfg about
		// the entry node of the corresponding function
		for (TacFormalParam formalParam : attsParamList.getFormalParamList()) {
			if (formalParam.hasDefault()) {
				Cfg defaultCfg = formalParam.getDefaultCfg();
				for (Iterator<CfgNode> defaultIter = defaultCfg.dfPreOrder().iterator(); defaultIter.hasNext(); ) {
					CfgNode defaultNode = (CfgNode) defaultIter.next();
					defaultNode.setDefaultParamPrep(entryNode);
				}
			}
		}

		// set function parameters
		function.setParams(attsParamList.getFormalParamList());

		// construct inner Cfg
		TacAttributes attsStat = this.inner_statement_list(node.getChild(statNum));

		// embed inner Cfg into function's frame Cfg
		connect(entryNode, attsStat.getCfg());
		connect(attsStat.getCfg(), exitNode);

		// return empty Cfg (we don't put function Cfgs inside one another)
		CfgNode emptyNode = new CfgNodeEmpty();
		myAtts.setCfg(new Cfg(emptyNode, emptyNode));

		// pop function stack
		this.functionStack.removeLast();

		// optimize function's Cfg
		this.optimize(cfg);
	}

	// methodHelper ********************************************************************    

	TacFunction methodHelper(ParseNode node, int paramListNum, int statNum, String functionName, boolean isInterface) {

		// referencedom
		boolean isReference = 
				(node.getChild(2).getChild(0).getSymbol() == PhpSymbols.T_EPSILON) ? false : true;

		// the function's frame Cfg
		CfgNodeEntry entryNode = new CfgNodeEntry(node);
		CfgNodeExit exitNode = new CfgNodeExit(node);
		Cfg cfg = new Cfg(entryNode, exitNode, CfgEdge.NO_EDGE);

		// create function object
		TacFunction function;
		if(isInterface)
		{
			function = new TacFunction(
					functionName,
					cfg,
					this.makeReturnPlace(functionName),
					isReference,
					node,
					this.interfaceStack.getLast().getName());
		}
		else{
			function = new TacFunction(
					functionName,
					cfg,
					this.makeReturnPlace(functionName),
					isReference,
					node,
					this.classStack.getLast().getName());
		}
		// push the function's name onto the function stack
		this.functionStack.add(function);

		// construct parameter list
		TacAttributes attsParamList = this.parameter_list(node.getChild(paramListNum));

		// for all formal parameters:
		// if this param has a default cfg, inform all nodes of this default cfg about
		// the entry node of the corresponding function
		for (TacFormalParam formalParam : attsParamList.getFormalParamList()) {
			if (formalParam.hasDefault()) {
				Cfg defaultCfg = formalParam.getDefaultCfg();
				for (Iterator<CfgNode> defaultIter = defaultCfg.dfPreOrder().iterator(); defaultIter.hasNext(); ) {
					CfgNode defaultNode = (CfgNode) defaultIter.next();
					defaultNode.setDefaultParamPrep(entryNode);
				}
			}
		}

		// set function parameters
		function.setParams(attsParamList.getFormalParamList());

		// construct inner Cfg
		//TacAttributes attsStat = this.inner_statement_list(node.getChild(statNum).getChild(1));
		if(node.getChild(statNum).getChild(0).getSymbol()==PhpSymbols.T_SEMICOLON){
			function.SetAbstract(true);
		}
		else{
			// construct inner Cfg
			TacAttributes attsStat = this.inner_statement_list(node.getChild(statNum).getChild(1));


			// embed inner Cfg into function's frame Cfg
			connect(entryNode, attsStat.getCfg());
			connect(attsStat.getCfg(), exitNode);
		}
		// pop function stack
		this.functionStack.removeLast();

		// optimize function's Cfg
		this.optimize(cfg);

		return function;
	}

	//  ********************************************************************************

	// creates an empty constructor for the given class name
	TacFunction constructorHelper(ParseNode node, String className) {

		// the constructor's empty Cfg
		CfgNodeEntry entryNode = new CfgNodeEntry(node);
		CfgNodeExit exitNode = new CfgNodeExit(node);
		connect(entryNode, exitNode);
		Cfg cfg = new Cfg(entryNode, exitNode, CfgEdge.NO_EDGE);

		// create function object
		String functionName = className + InternalStrings.methodSuffix;
		TacFunction function = new TacFunction(
				functionName,
				cfg,
				this.makeReturnPlace(functionName),
				false,
				node,
				className);

		// set function parameters
		function.setParams(new LinkedList<TacFormalParam>());

		return function;
	}

	//  generateShadows() **************************************************************

	// generate shadow variables for every function in this.userFunctions
	public void generateShadows() {
		Iterator<TacFunction> shadowIter = this.userFunctions.values().iterator();
		while (shadowIter.hasNext()) {
			TacFunction userFunction = (TacFunction) shadowIter.next();
			if (userFunction.isMain()) {
				continue;   // skip the main function
			}
			this.generateShadows(userFunction);
		}
	}

	//  generateShadows(TacFunction) ***************************************************

	// generates shadow variables for the given function;
	// don't call this function before calling transformGlobals(), otherwise
	// you will miss some g-shadows
	private void generateShadows(TacFunction function) {

		SymbolTable symTab = function.getSymbolTable();

		// G-SHADOWS

		// for each variable in the main function...
		for (Iterator<Variable> iter = this.mainSymbolTable.getVariablesColl().iterator(); iter.hasNext(); ) {
			Variable var = (Variable) iter.next();
			// no need to create shadows for temporaries
			// [what about arrays and array elements?]
			if (var.isTemp()) {
				continue;
			}
			symTab.addGShadow(var);
		}

		// F-SHADOWS

		// for each formal parameter of this function...
		for (Iterator<TacFormalParam> iter = function.getParams().iterator(); iter.hasNext();) {
			TacFormalParam param = (TacFormalParam) iter.next();
			Variable var = param.getVariable();
			// no need to create shadows for arrays and array elements
			if (var.isArray() || var.isArrayElement()) {
				continue;
			}
			symTab.addFShadow(var);
		}

	}

	// functionCallHelper **************************************************************

	// constructs a sub-cfg for a function call and returns it;
	// if "backpatch" is set to false, "function" must be non-null
	// (since otherwise, there would be no call to the nodes' setFunction();
	// calledFuncName: name of the called function or method (must not be null)
	// isMethod:       is this a method call? (or a function call?)
	// calledFunction: object of the called function 
	//                 (either this.someMethod or this.unknownFunction or null)
	Cfg functionCallHelper(
			String calledFuncName, boolean isMethod, TacFunction calledFunction, List<TacActualParam> paramList, 
			TacPlace tempPlace, boolean backpatch, ParseNode parseNode, String className,
			Variable object) {

		// if this is a call to the "define" function, we return a one-node cfg
		// containing only the special define node
		if (calledFuncName.equals("define")) {

			// extract params
			Iterator<TacActualParam> paramIter = paramList.iterator();
			TacPlace setMe = paramIter.next().getPlace();
			TacPlace setTo=null;
			if (paramIter.hasNext())
			{
				setTo = paramIter.next().getPlace();
			}
			TacPlace caseInsensitive;
			if (paramIter.hasNext()) {
				caseInsensitive = ((TacActualParam) paramIter.next()).getPlace();
			} else {
				// default value for the third parameter
				caseInsensitive = Constant.FALSE;
			}

			// if the defined constant is literal here, we can try to add
			// it to the constants table
			if (setMe.isLiteral()) {
				this.makeConstantPlace(setMe.toString());
			}

			CfgNodeDefine defineNode = new CfgNodeDefine(
					setMe, setTo, caseInsensitive, parseNode);
			return new Cfg(defineNode, defineNode);
		}

		// if this is a call to a builtin function, we return a one-node cfg
		// containing only the special "builtin call" node
		//BuiltinFunctions.PrintBuiltinFunctions();
		if (BuiltinFunctions.isBuiltinFunction(calledFuncName)) {

			CfgNodeCallBuiltin builtinNode = 
					new CfgNodeCallBuiltin(calledFuncName, paramList, tempPlace, parseNode);
			return new Cfg(builtinNode, builtinNode);
		}

		Literal funcNameLit = new Literal(calledFuncName);
		Variable returnVariable = (Variable) this.makeReturnPlace(calledFuncName);

		TacFunction enclosingFunction = (TacFunction) this.functionStack.getLast();

		// create nodes
		CfgNodeCallPrep prep = new CfgNodeCallPrep(parseNode);
		CfgNodeCall call = new CfgNodeCall(
				funcNameLit, calledFunction, parseNode, enclosingFunction,
				returnVariable, tempPlace, paramList, object);
		CfgNodeCallRet callRet = new CfgNodeCallRet(parseNode);

		// connect nodes
		connect(prep, call);
		connect(call, callRet);

		// update backpatching list (if necessary)
		if (backpatch) {
			if (isMethod) {
				//this.methodCalls.add(prep);

				this.addMethodCall(enclosingFunction, prep);
				call.setCalleeClassName(className);
			} else {
				//this.functionCalls.add(prep);
				this.addFunctionCall(enclosingFunction, prep);
			}
		}

		// inform the current function that it contains this call
		//enclosingFunction.addCall(call);

		return new Cfg(prep, callRet);
	}

	//  ********************************************************************************

	public void addFunctionCall(TacFunction enclosingFunction, CfgNodeCallPrep prepNode) {
		List<CfgNodeCallPrep> nodeList = this.functionCalls.get(enclosingFunction);
		if (nodeList == null) {
			nodeList = new LinkedList<CfgNodeCallPrep>();
			this.functionCalls.put(enclosingFunction, nodeList);
		}
		nodeList.add(prepNode);
	}

	public void addFunctionCalls(TacFunction enclosingFunction, List<CfgNodeCallPrep> prepNodes) {
		List<CfgNodeCallPrep> nodeList = this.functionCalls.get(enclosingFunction);
		if (nodeList == null) {
			nodeList = new LinkedList<CfgNodeCallPrep>();
			this.functionCalls.put(enclosingFunction, nodeList);
		}
		nodeList.addAll(prepNodes);
	}

	public List<CfgNodeCallPrep> getFunctionCalls(TacFunction enclosingFunction) {
		List<CfgNodeCallPrep> retMe = this.functionCalls.get(enclosingFunction);
		if (retMe == null) {
			return Collections.emptyList();
		} else {
			return retMe;
		}
	}

	//  ********************************************************************************

	public void addMethodCall(TacFunction enclosingFunction, CfgNodeCallPrep prepNode) {
		List<CfgNodeCallPrep> nodeList = this.methodCalls.get(enclosingFunction);
		if (nodeList == null) {
			nodeList = new LinkedList<CfgNodeCallPrep>();
			this.methodCalls.put(enclosingFunction, nodeList);
		}
		nodeList.add(prepNode);
	}

	public void addMethodCalls(TacFunction enclosingFunction, List<CfgNodeCallPrep> prepNodes) {
		List<CfgNodeCallPrep> nodeList = this.methodCalls.get(enclosingFunction);
		if (nodeList == null) {
			nodeList = new LinkedList<CfgNodeCallPrep>();
			this.methodCalls.put(enclosingFunction, nodeList);
		}
		nodeList.addAll(prepNodes);
	}

	public List<CfgNodeCallPrep> getMethodCalls(TacFunction enclosingFunction) {
		List<CfgNodeCallPrep> retMe = this.methodCalls.get(enclosingFunction);
		if (retMe == null) {
			return Collections.emptyList();
		} else {
			return retMe;
		}
	}

	//  addSuperGlobalElements *********************************************************

	// explicitly adds pre-defined elements of the 
	// superglobal $_SERVER and $HTTP_SERVER_VARS arrays 
	public void addSuperGlobalElements() {

		String[] indices = { 
				"PHP_SELF", "SERVER_NAME", "HTTP_HOST", "HTTP_REFERER", 
				"HTTP_ACCEPT_LANGUAGE", "SERVER_SOFTWARE", "PHP_AUTH_USER", 
				"PHP_AUTH_PW", "PHP_AUTH_TYPE", "SCRIPT_NAME", "SCRIPT_FILENAME", 
				"REQUEST_URI", "QUERY_STRING", "SCRIPT_URI"
		};

		// *** $_SERVER[...] ***

		String superName = "$_SERVER";
		Variable superVar = this.superSymbolTable.getVariable(superName);
		Variable var = null;
		for (int i = 0; i < indices.length; i++) {
			// only add the variable if it does not exist yet
			var = this.superSymbolTable.getVariable(superName + "[" + indices[i] + "]");
			if (var == null) {
				this.makeArrayElementPlace(superVar, new Literal(indices[i]));
			}
		}

		// LATER: not so elegant...
		Variable argv = this.superSymbolTable.getVariable(superName + "[argv]");
		if (argv == null) {
			argv = (Variable) this.makeArrayElementPlace(superVar, new Literal("argv"));
		}
		var = this.superSymbolTable.getVariable(superName + "[argv][0]");
		if (var == null) {
			this.makeArrayElementPlace(argv, new Literal("0"));
		}

		// LATER: eliminate redundancy
		// *** $HTTP_SERVER_VARS[...] ***

		superName = "$HTTP_SERVER_VARS";
		superVar = this.superSymbolTable.getVariable(superName);
		for (int i = 0; i < indices.length; i++) {
			// only add the variable if it does not exist yet
			var = this.superSymbolTable.getVariable(superName + "[" + indices[i] + "]");
			if (var == null) {
				this.makeArrayElementPlace(superVar, new Literal(indices[i]));
			}
		}

		argv = this.superSymbolTable.getVariable(superName + "[argv]");
		if (argv == null) {
			argv = (Variable) this.makeArrayElementPlace(superVar, new Literal("argv"));
		}
		var = this.superSymbolTable.getVariable(superName + "[argv][0]");
		if (var == null) {
			this.makeArrayElementPlace(argv, new Literal("0"));
		}

	}

	// makeArrayElementPlace ***********************************************************

	// - marks the enclosing array as array
	// - determines if the enclosing array is superglobal or not
	// - creates the array element and sets its properties
	// - informs the enclosing array about the element
	Variable makeArrayElementPlace(TacPlace arrayPlace, TacPlace offsetPlace) {

		// the enclosing array
		Variable arrayVar = arrayPlace.getVariable();

		// mark it as array
		if (arrayVar.isArray() == false) {
			arrayVar.setIsArray(true);
		}
		String offsetString = offsetPlace.toString();

		// if the enclosing array is superglobal, then the array
		// element is superglobal as well
		boolean superGlobal;
		SymbolTable symbolTable;
		if (arrayVar.isSuperGlobal()) {
			symbolTable = this.superSymbolTable;
			superGlobal = true;
		} else {
			symbolTable = 
					(SymbolTable) ((TacFunction) this.functionStack.getLast()).getSymbolTable();
			superGlobal = false;
		}

		// name for the array element
		String arrayElementName = arrayVar.getName() + "[" + offsetString + "]";

		// look if the array element already exists; we only have to do more work
		// if it hasn't been created yet
		Variable arrayElementVar = symbolTable.getVariable(arrayElementName);
		if (arrayElementVar == null) {

			// add it to the symbol table
			arrayElementVar = new Variable(arrayElementName, symbolTable);
			symbolTable.add(arrayElementVar);

			// set array element attributes
			arrayElementVar.setArrayElementAttributes(
					arrayVar, offsetPlace);
			arrayElementVar.setIsSuperGlobal(superGlobal);

			// inform enclosing array about the element
			arrayVar.addElement(arrayElementVar);
		}

		return arrayElementVar;
	}

	// arrayPairListHelper *************************************************************    

	// - creates the array element and
	//   creates a Cfg node which either assigns a value to the array element
	//   or makes the array element a reference to the given valuePlace
	CfgNode arrayPairListHelper(
			TacPlace arrayPlace, TacPlace offsetPlace, TacPlace valuePlace, 
			boolean reference, ParseNode node) {

		Variable arrayElementPlace = this.makeArrayElementPlace(arrayPlace, offsetPlace);

		// assign value to array element or create reference
		if (reference) {
			return (new CfgNodeAssignRef(arrayElementPlace, (Variable) valuePlace, node));
		} else {
			return (new CfgNodeAssignSimple(arrayElementPlace, valuePlace, node));
		}

	}

	// encapsListHelper ****************************************************************

	// encaps_list -> encaps_list, <some token>
	// - encapsList
	void encapsListHelper(ParseNode node, TacAttributes myAtts) {

		TacAttributes attsList = this.encaps_list(node.getChild(0));
		TacPlace stringPlace = new Literal(node.getChild(1).getLexeme(), false);
		if(attsList.getEncapsList()==null){
			//		attsList.setEncapsList(new EncapsList());
		}

		EncapsList encapsList = attsList.getEncapsList();		
		encapsList.add((Literal) stringPlace);
		myAtts.setEncapsList(encapsList);

	}

	// exprVarHelper *******************************************************************

	// returns the right place for   
	// $ { expr }
	// no matter if it is encountered within double quotes or not
	TacPlace exprVarHelper(TacPlace exprPlace) {

		TacPlace myPlace = null;
		if (exprPlace.isLiteral()) {

			// intended transformations for literals:
			// ${'123'}  -->  ${'123'}
			// ${'1xy'}  -->  ${'1xy'}
			// ${'foo'}  -->  $foo
			// ${123}    -->  ${'123'}
			// ${1xy}    -->  syntax error
			// ${foo}    -->  $foo      // means that we are inside double quotes*
			//
			// *since otherwise we would not be inside the "literal" branch,
			//  but inside the "constant" branch below
			//
			// no need to set dependency relation here

			String literal = exprPlace.toString();

			if (Character.isDigit(literal.charAt(0))) {
				myPlace = this.makePlace("${" + literal + "}");
			} else {
				myPlace = this.makePlace("$" + literal);
			}

		} else if (exprPlace.isVariable()) {
			myPlace = this.makePlace("${" + exprPlace.getVariable().getName() + "}");
			myPlace.getVariable().setDependsOn(exprPlace);
		} else if (exprPlace.isConstant()) {
			myPlace = this.makePlace("${" + exprPlace.getConstant().getLabel() + "}");
			myPlace.getVariable().setDependsOn(exprPlace);
		} else {
			throw new RuntimeException("SNH");
		}

		return myPlace;
	}

	// foreachHelper *******************************************************************

	// attsArray: the array to be run through ($arr in the example below)
	private void foreachHelper(ParseNode node, TacAttributes attsArray, TacAttributes myAtts) {
		// translation of
		//
		// foreach ($arr AS [$key =>] $value) {...}
		//
		// must have the same result as the translation of
		//
		// reset($arr);
		// while (list([$key], $value) = each($arr)) {...}

		// copy the array into a temporary to ensure correct semantics
		Variable arrayPlace = this.newTemp();
		CfgNode backupNode = new CfgNodeAssignSimple(arrayPlace, attsArray.getPlace(), node);

		// create nodes for calls to reset() and each()
		List<TacActualParam> paramList = new LinkedList<TacActualParam>();
		paramList.add(new TacActualParam(arrayPlace, false));
		int logId = this.tempId;
		// place for the array returned by each(); can also be used
		// for reset() since we don't need its return value
		TacPlace tempPlace = this.newTemp();

		Cfg resetCallCfg = this.functionCallHelper(
				"reset", false, null, paramList,
				tempPlace, true, node, null, null);

		Cfg eachCallCfg = this.functionCallHelper(
				"each", false, null, paramList,
				tempPlace, true, node, null, null);


		connect(attsArray.getCfg(), backupNode);
		connect(backupNode, resetCallCfg.getHead());
		connect(resetCallCfg.getTail(), eachCallCfg.getHead());

		// node testing whether to stay inside the loop or not
		CfgNode ifNode = new CfgNodeIf(
				tempPlace, 
				Constant.TRUE, 
				TacOperators.IS_EQUAL,
				node);

		// end node for the whole foreach construct
		CfgNode endNode = new CfgNodeEmpty();

		// assigning the array returned by each() to the right variables
		TacAttributes attsOptional = this.foreach_optional_arg(node.getChild(5));
		// we need to distinguish whether there is a "foreach_optional_arg" or
		// not (i.e. if there is a "=> w_cvar" or not)
		if (attsOptional == null) {

			// there is no foreach_optional_arg, which means that there is
			// only a value (instead of key => value)
			//Mona Nashaat:change rw_cvar into variable
			//			TacAttributes attsValue = this.variable(node.getChild(4));
			TacAttributes attsValue = this.foreach_variable(node.getChild(4));

			TacPlace tempPlaceValue = this.makeArrayElementPlace(
					tempPlace, new Literal("1"));

			CfgNode valueNode = new CfgNodeAssignSimple(
					(Variable) attsValue.getPlace(), tempPlaceValue, node.getChild(4));

			connect(eachCallCfg.getTail(), attsValue.getCfg());
			connect(attsValue.getCfg(), valueNode);
			connect(valueNode, ifNode);

		} else {

			// there is a foreach_optional_arg, which means that there
			// is a "key => value" mapping
			//Mona Nashaat:change rw_cvar into variable
			TacAttributes attsKey = this.foreach_variable(node.getChild(4));
			TacAttributes attsValue = attsOptional;

			TacPlace tempPlaceKey = this.makeArrayElementPlace(
					tempPlace, new Literal("0"));
			TacPlace tempPlaceValue = this.makeArrayElementPlace(
					tempPlace, new Literal("1"));

			CfgNode keyNode = new CfgNodeAssignSimple(
					(Variable) attsKey.getPlace(), tempPlaceKey, node.getChild(4));

			CfgNode valueNode = new CfgNodeAssignSimple(
					(Variable) attsValue.getPlace(), tempPlaceValue, node.getChild(5));

			connect(eachCallCfg.getTail(), attsKey.getCfg());
			connect(attsKey.getCfg(), attsValue.getCfg());
			connect(attsValue.getCfg(), keyNode);
			connect(keyNode, valueNode);
			connect(valueNode, ifNode);
		}

		this.resetId(logId);

		// prepare break and continue stacks for loop body
		this.continueTargetStack.add(eachCallCfg.getHead());
		this.breakTargetStack.add(endNode);

		// loop body
		TacAttributes attsStatement = this.foreach_statement(node.getChild(7));
		connect(ifNode, attsStatement.getCfg().getHead(), CfgEdge.TRUE_EDGE);
		// DON'T use this to connect the loop body to the loop header:
		// if the loop body contains a return statement, it won't lead to the
		// function's exit node this way!
		//connect(attsStatement.getCfg().getTail(), eachCallCfg.getHead());
		// use the following line instead:
		connect(attsStatement.getCfg(), eachCallCfg.getHead());
		connect(ifNode, endNode, CfgEdge.FALSE_EDGE);

		this.continueTargetStack.removeLast();
		this.breakTargetStack.removeLast();

		myAtts.setCfg(new Cfg(attsArray.getCfg().getHead(), endNode));
	}

	//  backpatch **********************************************************************

	// preliminary backpatching
	public void backpatch() {
		backpatch(false, false, null, null);
	}

	// backpatching for function calls
	// - riskMethods: perform method disambiguation based on method name?
	//   only do this if there will be no further file inclusions
	// - finalPass: issues warnings for unresolved calls, and performs some
	//   source code replacements
	// - typeAnalysis: can also be null
	public void backpatch(boolean riskMethods, boolean finalPass, 
			TypeAnalysis typeAnalysis, CallGraph callGraph) {

		// method backpatching
		for (List<CfgNodeCallPrep> callList : this.methodCalls.values()) {
			for (CfgNodeCallPrep prepNode : callList) {

				CfgNodeCall callNode = prepNode.getCallNode();
				CfgNodeCallRet retNode = prepNode.getCallRetNode();

				// determine reachability of this call node
				boolean reachable = true;
				TacFunction enclosingFunction = callNode.getEnclosingFunction();
				if (callGraph != null) {
					if (!callGraph.reachable(enclosingFunction)) {
						reachable = false;
					}
				}

				TacPlace functionNamePlace = prepNode.getFunctionNamePlace();

				//System.out.println("backpatching: " + callNode.getLoc());

				// note: only methods with literal names have been added to the backpatching list

				// note: if some called function or method can't be found, it could
				// be because the node that includes the file containing this function
				// is unreachable (e.g., the node is inside a function that is never
				// called); this can be identified by inspecting the call graph (dumped in
				// calledby.txt); in such cases, the warning can be ignored;

				// try to retrieve the callee...

				TacFunction callee = null;
				Map<String, TacFunction> class2Method = this.userMethods.get(functionNamePlace.toString());

				// only if we have a class2Method mapping for this method name, there is
				// a chance that we can retrieve the callee method; otherwise, it means that
				// have haven't found a method definition with this name anywhere
				if (class2Method != null) {
					String calleeClassName = callNode.getCalleeClassName();

					if (calleeClassName != null) {
						// we know the class to which the called method belongs, so we
						// can try to retrieve the corresponding method
						callee = class2Method.get(calleeClassName);
					} else if (riskMethods) {
						// we don't know the class to which the called method belongs, so
						// we hope that there is only one method with this name;
						// only do this during the final pass, since you can get strange
						// effects otherwise (e.g.: a class with an appropriate method name
						// is included first, but this method is not called in reality; in
						// a later inclusion pass, the real method is included...)
						if (class2Method.size() == 1) {
							callee = class2Method.values().iterator().next();
						} else {
							// there is more than one method with this name, so
							// which one should we take?
							boolean resolved = false;

							// try to ask type analysis
							if (typeAnalysis != null) {
								Set<Type> types = typeAnalysis.getType(callNode.getObject(), callNode);
								if (types != null && types.size() == 1) {
									// resolved it!
									Type type = types.iterator().next();
									callee = class2Method.get(type.getClassName());
									if (callee != null) {
										resolved = true;
									} else {
										// type analysis has made a mistake
										//System.out.println(callNode.getLoc());
										//System.out.println("Inferred type: " + type.getClassName());
										//System.out.println("Possible types: " + class2Method.keySet());
										//throw new RuntimeException("Error during type inference");
									}
								}
							}

							// if we have left it unresolved...
							if (finalPass && !resolved && reachable) {
								System.out.println("reachable: " + enclosingFunction.getName());
								System.out.println("Warning: can't resolve method call (same name in different classes)");
								System.out.println("- name:    " + functionNamePlace);
								System.out.println("- call:    " + prepNode.getLoc());
								System.out.println("- classes: " + class2Method.keySet());
							}
						}
					}

				} else {

					if (finalPass && reachable) {
						System.out.println("Warning: can't resolve method call (no definition found)");
						System.out.println("- name:    " + functionNamePlace);
						System.out.println("- call:    " + prepNode.getLoc());
					}

				}

				if (callee == null) {

					if (finalPass) {
						// replace the three call nodes with a cfgnodecallunknown
						this.replaceUnknownCall(prepNode, functionNamePlace.toString(), true);
					}
					continue;

				} else {
					List<TacActualParam> actualParams = prepNode.getParamList();
					List<TacFormalParam> formalParams = callee.getParams();
					int actualSize = actualParams.size();
					int formalSize = formalParams.size();
					if (actualSize != formalSize) {
						if (actualSize > formalSize) {
							// more actual than formal params; either a bug or a 
							// varargs occurrence; LATER: think about this;
							// for now: delete additional params
							System.out.println("Warning: More actual than formal params");
							System.out.println("- call:    " + prepNode.getLoc());
							System.out.println("- callee:  " + prepNode.getFunctionNamePlace().toString());
							System.out.println("- decl:    " + callee.getLoc());
							while (actualParams.size() > formalParams.size()) {
								actualParams.remove(actualParams.size() - 1);
							}
						} else {

							// more formal than actual params; 
							// this is only ok if the missing actuals are matched by
							// default formals; otherwise, it is a bug

							if (finalPass) {
								// find out if this is a bug
								int i = 0;
								for (TacFormalParam formalParam : formalParams) {
									i++;
									if (i <= actualSize) {
										continue;
									}
									if (!formalParam.hasDefault()) {
										System.out.println("Warning: Not enough actual params");
										System.out.println("- call:    " + prepNode.getLoc());
										System.out.println("- callee:  " + prepNode.getFunctionNamePlace().toString());
										System.out.println("- decl:    " + callee.getLoc());
										break;
									}
								}
							}
						}
					}
				}

				callNode.setCallee(callee);
				retNode.setRetVar((Variable) callee.getRetVar());
			}
		}

		// function backpatching
		//for (Iterator iter = this.functionCalls.iterator(); iter.hasNext(); ) {
		for (List<CfgNodeCallPrep> callList : this.functionCalls.values()) {
			for (CfgNodeCallPrep prepNode : callList) {

				//CfgNodeCallPrep prepNode = (CfgNodeCallPrep) iter.next();
				CfgNodeCall callNode = prepNode.getCallNode();
				CfgNodeCallRet retNode = (CfgNodeCallRet) callNode.getOutEdge(0).getDest();

				TacPlace functionNamePlace = prepNode.getFunctionNamePlace();

				// determine reachability of this call node
				boolean reachable = true;
				if (callGraph != null) {
					TacFunction enclosingFunction = callNode.getEnclosingFunction();
					if (!callGraph.reachable(enclosingFunction)) {
						reachable = false;
					}
				}

				// only functions with literal names have been added to the backpatching list;
				// the others should be linked with their corresponding function objects
				// (or the special _unkownFunction) by the CP
				TacFunction callee = (TacFunction) this.userFunctions.get(functionNamePlace.toString());
				if (callee == null) {

					if (BuiltinFunctions.isBuiltinFunction(functionNamePlace.toString())) {

						// since we are now using a separate cfg node for calls to
						// builtin functions, this case should not happen 
						if (true) throw new RuntimeException("SNH");

					} else {

						// not having information about a non-builtin function IS bad,
						// since it could contain sensitive sinks
						if (finalPass) {

							if (reachable) {
								System.out.println("Warning: can't find function " + functionNamePlace);
								System.out.println("- " + prepNode.getLoc());
							}

							// replace the three call nodes with a cfgnodecallunknown

							this.replaceUnknownCall(prepNode, functionNamePlace.toString(), false);
						}

						continue;
					}

				} else {

					List<TacActualParam> actualParams = prepNode.getParamList();
					List<TacFormalParam> formalParams = callee.getParams();
					int actualSize = actualParams.size();
					int formalSize = formalParams.size();
					if (actualSize != formalSize) {
						if (actualSize > formalSize) {
							// more actual than formal params; either a bug or a 
							// varargs occurrence; LATER: think about this;
							// for now: delete additional params
							System.out.println("Warning: More actual than formal params");
							System.out.println("- call:    " + prepNode.getLoc());
							System.out.println("- callee:  " + prepNode.getFunctionNamePlace().toString());
							System.out.println("- decl:    " + callee.getLoc());
							while (actualParams.size() > formalParams.size()) {
								actualParams.remove(actualParams.size() - 1);
							}
						} else {
							// more formal than actual params; 
							// this is only ok if the missing actuals are matched by
							// default formals; otherwise, it is a bug

							if (finalPass) {
								// find out if this is a bug
								int i = 0;
								for (TacFormalParam formalParam : formalParams) {
									i++;
									if (i <= actualSize) {
										continue;
									}
									if (!formalParam.hasDefault()) {
										System.out.println("Warning: Not enough actual params");
										System.out.println("- call:    " + prepNode.getLoc());
										System.out.println("- callee:  " + prepNode.getFunctionNamePlace().toString());
										//Mona Nashaat System.out.println("- decl:    " + callee.getLoc());
										break;
									}
								}
							}
						}
					}
				}

				callNode.setCallee(callee);
				retNode.setRetVar((Variable) callee.getRetVar());
			}
		}
	}

	private void replaceUnknownCall(CfgNodeCallPrep prepNode, 
			String functionName, boolean isMethod) {

		CfgNodeCallRet callRet = prepNode.getCallRetNode();

		// the replacement node
		CfgNodeCallUnknown callUnknown = new CfgNodeCallUnknown(
				functionName, 
				prepNode.getParamList(), 
				callRet.getTempVar(), 
				prepNode.getParseNode(),
				isMethod);

		// update predecessors:
		// redirect edges that enter the prep node
		for (CfgEdge inEdge : prepNode.getInEdges()) {
			inEdge.setDest(callUnknown);
			callUnknown.addInEdge(inEdge);
		}

		// update successor
		List<CfgNode> succs = prepNode.getCallRetNode().getSuccessors();
		if (succs.size() == 1) {
			CfgNode succ = succs.get(0);
			succ.removeInEdge(callRet);
			connect(callUnknown, succ);
		} else if (succs.size() == 0) {
			// happens here, for instance:
			// die(xyz());
		} else {
			throw new RuntimeException("SNH");
		}

	}



	// *********************************************************************************    
	// PARSE RULE METHODS **************************************************************    
	// *********************************************************************************    

	// start ***************************************************************************

	void start(ParseNode node) {

		// always:
		// -> top_statement_list

		// entry and exit nodes for the (virtual) main function;
		// no need for a CfgNodeExitPrep here
		CfgNode entryNode = new CfgNodeEntry(node);
		CfgNode exitNode = new CfgNodeExit(node);

		// Cfg for the main function
		Cfg cfg = new Cfg(entryNode, exitNode, CfgEdge.NO_EDGE);

		// name of main function
		String mainFunctionName = InternalStrings.mainFunctionName;

		// function object
		TacFunction function = new TacFunction(
				mainFunctionName, 
				cfg, 
				this.makeReturnPlace(mainFunctionName),
				false,
				node, "");
		// not necessary, but clean
		List<TacFormalParam> l = Collections.emptyList();
		function.setParams(l);
		function.setIsMain(true);

		// add the function to the list of user functions
		this.userFunctions.put(mainFunctionName, function);

		// make shortcut to the main function
		this.mainFunction = function;

		// make shortcut to the symbol table of the main function
		this.mainSymbolTable = function.getSymbolTable();

		// push function onto the function stack
		this.functionStack.add(function);

		// descend into parse tree        
		TacAttributes atts0 = this.top_statement_list(node.getChild(0));

		// embed recursively constructed Cfg into the function's frame Cfg
		connect(entryNode, atts0.getCfg());
		connect(atts0.getCfg(), exitNode);

		this.functionStack.removeLast();
		this.optimize(cfg);

		// transform entries of the $GLOBALS array into corresponding
		// global variables (= local variables of the main function);
		// note: $GLOBALS-stuff can't be used for formal params, so it is
		// sufficient to traverse the CFG's
		for (Iterator<TacFunction> iter = this.userFunctions.values().iterator(); iter.hasNext();) {
			TacFunction userFunction = (TacFunction) iter.next();
			this.transformGlobals(userFunction.getCfg());
		}

	}

	// top_statement_list **************************************************************    

	// - cfg
	TacAttributes top_statement_list(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();        
		ParseNode firstChild = node.getChild(0);
		//   System.out.println("Yaraab. My Name is :"+firstChild.getName());

		switch(firstChild.getSymbol()) {
		// -> top_statement_list:l1 top_statement:l2
		case PhpSymbols.top_statement_list:
			// case 237:
		{
			int logId = this.tempId;
			TacAttributes atts0 = this.top_statement_list(firstChild);
			TacAttributes atts1 = this.top_statement(node.getChild(1));

			connect(atts0.getCfg(), atts1.getCfg());
			myAtts.setCfg(new Cfg(
					atts0.getCfg().getHead(),
					atts1.getCfg().getTail(),
					atts1.getCfg().getTailEdgeType()));
			this.resetId(logId);
			break;
		}
		/* empty */
		case PhpSymbols.T_EPSILON:
		{
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			break;
		}


		}
		return myAtts;
	}

	// Mona Nashaat **************************************************************    
	// namespace_name **************************************************************    

	TacAttributes namespace_name(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();    
		myAtts.setEncapsList(new EncapsList());

		// T_STRING
		if (node.children().size()==1)
		{
			//here
			ParseNode firstChild = node.getChild(0);
			myAtts.getEncapsList().add(new Literal(firstChild.tokenContent()));
			myAtts.setPlace(new Literal(firstChild.getLexeme()));
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
		}
		//namespace_name T_NS_SEPARATOR T_STRING
		else
		{
			ParseNode firstChild = node.getChild(0);
			myAtts= this.namespace_name(firstChild);        
			myAtts.getEncapsList().add(new Literal(node.getChild(2).getLexeme()));
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
		}      

		return myAtts;
	}


	// top_statement *******************************************************************

	// - cfg
	TacAttributes top_statement(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();
		// Mona Nashaat changed statement in PhpSymbols to 229 
		ParseNode firstChild = node.getChild(0);
		//	System.out.println("firstchild name "+firstChild.name()+" symbol: "+ firstChild.getSymbol());
		//	System.out.println("statment"+ PhpSymbols.statement);
		switch(firstChild.getSymbol()) {

		// -> statement        
		case PhpSymbols.statement:
		{
			TacAttributes atts0 = this.statement(firstChild);
			myAtts.setCfg(atts0.getCfg());
			break;
		}

		// -> function_declaration_statement
		case PhpSymbols.function_declaration_statement:
		{
			TacAttributes atts0 = this.function_declaration_statement(firstChild);
			myAtts.setCfg(atts0.getCfg());
			break;
		}

		// -> class_declaration_statement
		case PhpSymbols.class_declaration_statement:
		{
			TacAttributes atts0 = this.class_declaration_statement(firstChild);
			myAtts.setCfg(atts0.getCfg());
			break;
		}

		// -> T_HALT_COMPILER();
		case PhpSymbols.T_HALT_COMPILER:
		{
			// put an empty node in place of Halt_compiler decleration
			// (we don't need the declaration in the resulting cfg)
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			break;
		}
		// All Rules starts with T_NAMESPACE
		case PhpSymbols.T_NAMESPACE:
		{
			// ->  T_NAMESPACE namespace_name T_SEMICOLON
			if(node.getChild(2).getSymbol()==PhpSymbols.T_SEMICOLON)
			{
				// collect information about the namespace and register
				// the TacNameSpace in userNameSpaces        		   
				TacAttributes atts0 =   this.namespace_name(node.getChild(1));
				String namespaceName=atts0.getEncapsListString();
				TacNamespace n = new TacNamespace(namespaceName, node);
				TacNamespace existingNamespace = this.userNameSpaces.get(namespaceName);
				if (existingNamespace == null) {
					this.userNameSpaces.put(namespaceName, n);
					this.nameSpaceStack.add(n);                	                 	   
					this.nameSpaceStack.removeLast();                  
				}             
				// put an empty node in place of Namespace declarations
				// (we don't need the declaration in the resulting cfg)
				CfgNode emptyNode = new CfgNodeEmpty();
				myAtts.setCfg(new Cfg(emptyNode, emptyNode));
				break;
			}

			// ->  T_NAMESPACE namespace_name { top_statement_list }
			else if(node.getChild(2).getSymbol()==PhpSymbols.T_OPEN_CURLY_BRACES)
			{
				// collect information about the namespace and register
				// the TacNameSpace in userNameSpaces
				TacAttributes atts0 =   this.namespace_name(node.getChild(1));
				String namespaceName=atts0.getEncapsListString();


				TacNamespace n = new TacNamespace(namespaceName, node);
				TacNamespace existingNamespace = this.userNameSpaces.get(namespaceName);
				if (existingNamespace == null) {
					this.userNameSpaces.put(namespaceName, n);
					this.nameSpaceStack.add(n);   
					this.top_statement_list(node.getChild(3));
					this.nameSpaceStack.removeLast();                  
				}
				CfgNode emptyNode = new CfgNodeEmpty();
				myAtts.setCfg(new Cfg(emptyNode, emptyNode));
				break;
			}

			// ->  T_NAMESPACE { top_statement_list }
			else if(node.getChild(2).getSymbol()==PhpSymbols.top_statement_list)
			{

				// Default NameSpace, cannot collect any information
				this.top_statement_list(node.getChild(2));

				CfgNode emptyNode = new CfgNodeEmpty();
				myAtts.setCfg(new Cfg(emptyNode, emptyNode));
				break;
			}
		}
		// ->  T_USE use_declarations T_SEMICOLON  
		case PhpSymbols.T_USE:
		{
			TacAttributes atts0= this.use_declarations(node.getChild(1));
			// not implemented in current PHP version
			myAtts.setCfg(atts0.getCfg());
			//CfgNode emptyNode = new CfgNodeEmpty();
			//myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			break;
		}

		// ->  constant_declaration T_SEMICOLON 
		case PhpSymbols.constant_declaration:
		{	
			TacAttributes atts0= this.constant_declaration(node.getChild(0));
			// not implemented in current PHP version
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			break;        	    
		}

		}
		return myAtts;
	}

	// use_declarations *****************************************************************

	// - cfg
	TacAttributes use_declarations(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> use_declarations T_COMMA use_declaration
		case PhpSymbols.use_declarations:
		{
			int logId = this.tempId;
			TacAttributes atts0 = this.use_declarations(firstChild);
			TacAttributes atts1 = this.use_declaration(node.getChild(2));

			connect(atts0.getCfg(), atts1.getCfg());
			myAtts.setCfg(new Cfg(
					atts0.getCfg().getHead(),
					atts1.getCfg().getTail(),
					atts1.getCfg().getTailEdgeType()));
			this.resetId(logId);
			break;
		}
		// -> use_declaration
		case PhpSymbols.use_declaration:
		{
			TacAttributes atts0 = this.use_declaration(firstChild);
			myAtts.setCfg(atts0.getCfg());
			break;
		}

		}
		return myAtts;

	}

	// use_declaration *****************************************************************

	// - cfg
	TacAttributes use_declaration(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> namespace_name
		// -> namespace_name T_AS T_STRING
		case PhpSymbols.namespace_name:
		{            	 
			TacAttributes atts0 =  this.namespace_name(firstChild);
			myAtts.setCfg(atts0.getCfg());
			break;
		}

		// -> T_NS_SEPARATOR namespace_name
		//->T_NS_SEPARATOR namespace_name T_AS T_STRING 
		case PhpSymbols.T_NS_SEPARATOR:
		{
			TacAttributes atts0 =  this.namespace_name(node.getChild(1));
			myAtts.setCfg(atts0.getCfg());
			break;
		}

		}
		return myAtts;

	}

	// constant_declaration *****************************************************************

	// - cfg
	TacAttributes constant_declaration(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> constant_declaration T_COMMA T_STRING T_ASSIGN static_expr
		case PhpSymbols.constant_declaration:
		{
			int logId = this.tempId;
			TacAttributes atts0 = this.constant_declaration(firstChild);
			TacAttributes atts1 = this.static_expr(node.getChild(4));

			connect(atts0.getCfg(), atts1.getCfg());
			myAtts.setCfg(new Cfg(
					atts0.getCfg().getHead(),
					atts1.getCfg().getTail(),
					atts1.getCfg().getTailEdgeType()));
			this.resetId(logId);
			break;
		}
		// -> T_CONST T_STRING T_ASSIGN static_expr
		case PhpSymbols.T_CONST:
		{
			TacAttributes atts0 = this.static_expr(node.getChild(3));

			myAtts.setCfg(atts0.getCfg());
			break;
		}

		}
		return myAtts;

	}


	// inner_statement_list *****************************************************************

	// - cfg
	TacAttributes inner_statement_list(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> inner_statement_list inner_statement 
		case PhpSymbols.inner_statement_list:
		{
			int logId = this.tempId;
			TacAttributes atts0 = this.inner_statement_list(firstChild);
			TacAttributes atts1 = this.inner_statement(node.getChild(1));
			connect(atts0.getCfg(), atts1.getCfg());
			myAtts.setCfg(new Cfg(
					atts0.getCfg().getHead(),
					atts1.getCfg().getTail(),
					atts1.getCfg().getTailEdgeType()));
			this.resetId(logId);
			break;
		}

		// -> empty
		case PhpSymbols.T_EPSILON:
		{
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			break;
		}
		}

		return myAtts;
	}

	// inner_statement *****************************************************************

	// - cfg
	TacAttributes inner_statement(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> statement
		case PhpSymbols.statement:
		{
			TacAttributes atts0 = this.statement(firstChild);
			myAtts.setCfg(atts0.getCfg());
			break;
		}
		// -> function_declaration_statement
		case PhpSymbols.function_declaration_statement:
		{
			TacAttributes atts0 = this.function_declaration_statement(firstChild);
			myAtts.setCfg(atts0.getCfg());
			break;
		}
		// -> class_declaration_statement
		case PhpSymbols.class_declaration_statement:
		{
			TacAttributes atts0 = this.class_declaration_statement(firstChild);
			myAtts.setCfg(atts0.getCfg());
			break;
		}
		// -> T_HALT_COMPILER();
		case PhpSymbols.T_HALT_COMPILER:
		{
			// put an empty node in place of Halt_compiler decleration
			// (we don't need the declaration in the resulting cfg)
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			break;
		}       
		}

		return myAtts;
	}

	// statement ***********************************************************************

	// - cfg
	TacAttributes statement(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> { inner_statement_list }
		case PhpSymbols.T_OPEN_CURLY_BRACES:
		{
			TacAttributes atts1 = this.inner_statement_list(node.getChild(1));
			myAtts.setCfg(atts1.getCfg());
			break;
		}

		// -> T_IF ( expr ) ...
		case PhpSymbols.T_IF:
		{
			if (node.getChild(4).getSymbol() == PhpSymbols.statement) {
				// -> T_IF ( expr ) statement elseif_list else_single

				CfgNode endIfNode = new CfgNodeEmpty();

				int logId = this.tempId;
				TacAttributes attsExpr = this.expr(node.getChild(2));
				this.resetId(logId);
				TacAttributes attsStatement = this.statement(node.getChild(4));
				TacAttributes attsElse = this.else_single(node.getChild(6));
				TacAttributes attsElif = this.elseif_list(
						node.getChild(5), endIfNode, attsElse.getCfg().getHead());

				CfgNode ifNode = new CfgNodeIf(
						attsExpr.getPlace(), Constant.TRUE, TacOperators.IS_EQUAL,
						node.getChild(2));

				connect(attsExpr.getCfg(), ifNode);
				connect(ifNode, attsElif.getCfg(), CfgEdge.FALSE_EDGE);
				connect(ifNode, attsStatement.getCfg(), CfgEdge.TRUE_EDGE);
				connect(attsStatement.getCfg(), endIfNode);
				connect(attsElse.getCfg(), endIfNode);

				myAtts.setCfg(new Cfg(
						attsExpr.getCfg().getHead(),
						endIfNode));

			} else {
				// -> T_IF ( expr ) : inner_statement_list new_elseif_list 
				//    new_else_single T_ENDIF ;

				CfgNode endIfNode = new CfgNodeEmpty();

				int logId = this.tempId;
				TacAttributes attsExpr = this.expr(node.getChild(2));
				this.resetId(logId);
				TacAttributes attsStatement = this.inner_statement_list(node.getChild(5));
				TacAttributes attsElse = this.new_else_single(node.getChild(7));
				TacAttributes attsElif = this.new_elseif_list(
						node.getChild(6), endIfNode, attsElse.getCfg().getHead());

				CfgNode ifNode = new CfgNodeIf(
						attsExpr.getPlace(), Constant.TRUE, TacOperators.IS_EQUAL,
						node.getChild(2));

				connect(attsExpr.getCfg(), ifNode);
				connect(ifNode, attsElif.getCfg(), CfgEdge.FALSE_EDGE);
				connect(ifNode, attsStatement.getCfg(), CfgEdge.TRUE_EDGE);
				connect(attsStatement.getCfg(), endIfNode);
				connect(attsElse.getCfg(), endIfNode);

				myAtts.setCfg(new Cfg(
						attsExpr.getCfg().getHead(),
						endIfNode));
			}

			break;
		}

		// -> T_WHILE ( expr ) while_statement
		case PhpSymbols.T_WHILE:
		{
			CfgNode endWhileNode = new CfgNodeEmpty();
			this.breakTargetStack.add(endWhileNode);
			int logId = this.tempId;
			TacAttributes attsExpr = this.expr(node.getChild(2));
			this.resetId(logId); 
			this.continueTargetStack.add(attsExpr.getCfg().getHead());
			TacAttributes attsStatement = this.while_statement(node.getChild(4));

			CfgNode ifNode = new CfgNodeIf(
					attsExpr.getPlace(), Constant.TRUE, TacOperators.IS_EQUAL,
					node.getChild(2));

			connect(attsExpr.getCfg(), ifNode);
			connect(ifNode, attsStatement.getCfg(), CfgEdge.TRUE_EDGE);
			connect(ifNode, endWhileNode, CfgEdge.FALSE_EDGE);
			connect(attsStatement.getCfg(), attsExpr.getCfg());

			myAtts.setCfg(new Cfg(
					attsExpr.getCfg().getHead(),
					endWhileNode));

			this.continueTargetStack.removeLast();
			this.breakTargetStack.removeLast();

			break;
		}

		// -> T_DO statement T_WHILE ( expr ) ;
		case PhpSymbols.T_DO:
		{
			CfgNode endDoNode = new CfgNodeEmpty();
			this.breakTargetStack.add(endDoNode);
			TacAttributes attsStatement = this.statement(node.getChild(1));
			int logId = this.tempId;
			TacAttributes attsExpr = this.expr(node.getChild(4));
			this.resetId(logId);
			this.continueTargetStack.add(attsStatement.getCfg().getHead());

			CfgNode ifNode = new CfgNodeIf(
					attsExpr.getPlace(), Constant.TRUE, TacOperators.IS_EQUAL,
					node.getChild(4));

			connect(attsStatement.getCfg(), attsExpr.getCfg());
			connect(attsExpr.getCfg(), ifNode);
			connect(ifNode, attsStatement.getCfg(), CfgEdge.TRUE_EDGE);
			connect(ifNode, endDoNode, CfgEdge.FALSE_EDGE);

			myAtts.setCfg(new Cfg(
					attsStatement.getCfg().getHead(),
					endDoNode));

			this.continueTargetStack.removeLast();
			this.breakTargetStack.removeLast();

			break;
		}

		// -> T_FOR ( for_expr1 ; for_expr2 ; for_expr3 ) for_statement
		case PhpSymbols.T_FOR:
		{
			CfgNode endForNode = new CfgNodeEmpty();
			this.breakTargetStack.add(endForNode);

			TacAttributes attsExpr1 = this.for_expr(node.getChild(2));
			TacAttributes attsExpr2 = this.for_expr(node.getChild(4));
			TacAttributes attsExpr3 = this.for_expr(node.getChild(6));

			this.continueTargetStack.add(attsExpr3.getCfg().getHead());

			TacAttributes attsStatement = this.for_statement(node.getChild(8));

			CfgNode ifNode = new CfgNodeIf(
					attsExpr2.getPlace(), Constant.TRUE, TacOperators.IS_EQUAL,
					node.getChild(4));

			connect(attsExpr1.getCfg(), attsExpr2.getCfg());
			connect(attsExpr2.getCfg(), ifNode);
			connect(ifNode, attsStatement.getCfg(), CfgEdge.TRUE_EDGE);
			connect(ifNode, endForNode, CfgEdge.FALSE_EDGE);
			connect(attsStatement.getCfg(), attsExpr3.getCfg());
			connect(attsExpr3.getCfg(), attsExpr2.getCfg());

			myAtts.setCfg(new Cfg(
					attsExpr1.getCfg().getHead(),
					endForNode));

			this.continueTargetStack.removeLast();
			this.breakTargetStack.removeLast();

			break;
		}

		// -> T_SWITCH ( expr ) switch_case_list
		case PhpSymbols.T_SWITCH:
		{
			CfgNode endSwitchNode = new CfgNodeEmpty();
			CfgNode defaultJumpNode = new CfgNodeEmpty();

			this.continueTargetStack.add(endSwitchNode);
			this.breakTargetStack.add(endSwitchNode);

			TacAttributes attsExpr = this.expr(node.getChild(2));
			TacAttributes attsList = this.switch_case_list(node.getChild(4),
					attsExpr.getPlace(), defaultJumpNode, endSwitchNode);

			if (attsList.getDefaultNode() != null) {
				connect(defaultJumpNode, attsList.getDefaultNode());
			} else {
				connect(defaultJumpNode, endSwitchNode);
			}

			connect(attsExpr.getCfg(), attsList.getCfg());

			myAtts.setCfg(new Cfg(
					attsExpr.getCfg().getHead(),
					endSwitchNode));

			this.continueTargetStack.removeLast();
			this.breakTargetStack.removeLast();
			break;
		}

		// -> T_BREAK ...
		case PhpSymbols.T_BREAK:
		{
			if (node.getChild(1).getSymbol() == PhpSymbols.T_SEMICOLON) {
				// -> T_BREAK ;
				CfgNode cfgNode = new CfgNodeEmpty();
				CfgNode breakTarget = null;
				try {
					breakTarget = (CfgNode) this.breakTargetStack.getLast();
				} catch (NoSuchElementException e) {
					System.out.println("Invalid break statement:");
					//  System.out.println(node.getLoc());
					throw new RuntimeException(e.getMessage());
				}
				connect(cfgNode, breakTarget);
				myAtts.setCfg(new Cfg(cfgNode, cfgNode, CfgEdge.NO_EDGE));
			} else {
				// -> T_BREAK expr ;

				// check whether this expression is just a single T_LNUMBER:
				// expr -> expr_without_variable -> scalar -> common_scalar -> T_LNUMBER

				boolean isNumber = false;
				ParseNode maybeNumber = null;
				try {
					//Mona Nashaat
					//maybeNumber = node.getChild(1).getChild(0).getChild(0).getChild(0).getChild(0);
					maybeNumber = node.getChild(1).getChild(0).getChild(0).getChild(0);
					if (maybeNumber.getSymbol() == PhpSymbols.T_LNUMBER) {
						isNumber = true;
					}
				} catch (IndexOutOfBoundsException ex) {
					// do nothing
				}

				if (isNumber) {
					int breakDepth = Integer.parseInt(maybeNumber.getLexeme());

					CfgNode cfgNode = new CfgNodeEmpty();
					CfgNode breakTarget = (CfgNode) this.breakTargetStack.get(
							this.breakTargetStack.size() - breakDepth);
					connect(cfgNode, breakTarget);
					myAtts.setCfg(new Cfg(cfgNode, cfgNode, CfgEdge.NO_EDGE));

				} else {
					// the expression is more complicated, we leave it unresolved;
					// control could flow to any break target,
					// probably not used in real life
					System.out.println("Unsupported 'break' in file " + 
							this.file.getAbsolutePath() + ", line " + node.getLinenoLeft());
					throw new RuntimeException();
				}
			}

			break;
		}

		// -> T_CONTINUE ...
		case PhpSymbols.T_CONTINUE:
		{
			if (node.getChild(1).getSymbol() == PhpSymbols.T_SEMICOLON) {
				// -> T_CONTINUE ;
				CfgNode cfgNode = new CfgNodeEmpty();
				try {
					CfgNode continueTarget = (CfgNode) this.continueTargetStack.getLast();
					connect(cfgNode, continueTarget);
					myAtts.setCfg(new Cfg(cfgNode, cfgNode, CfgEdge.NO_EDGE));
				} catch (NoSuchElementException e) {
					System.out.println("Warning: Unsupported 'continue'");
					System.out.println("- " + node.getName());
					myAtts.setCfg(new Cfg(cfgNode, cfgNode));
				}
			} else {
				// -> T_CONTINUE expr ;


				// check whether this expression is just a single T_LNUMBER:
				// expr -> expr_without_variable -> scalar -> common_scalar -> T_LNUMBER

				boolean isNumber = false;
				ParseNode maybeNumber = null;
				try {
					maybeNumber = node.getChild(1).getChild(0).getChild(0).getChild(0);//.getChild(0);
					if (maybeNumber.getSymbol() == PhpSymbols.T_LNUMBER) {
						isNumber = true;
					}
				} catch (IndexOutOfBoundsException ex) {
					// do nothing
				}

				if (isNumber) {
					int continueDepth = Integer.parseInt(maybeNumber.getLexeme());

					CfgNode cfgNode = new CfgNodeEmpty();
					CfgNode continueTarget = (CfgNode) this.continueTargetStack.get(
							this.continueTargetStack.size() - continueDepth);
					connect(cfgNode, continueTarget);
					myAtts.setCfg(new Cfg(cfgNode, cfgNode, CfgEdge.NO_EDGE));

				} else {
					// the expression is more complicated, we leave it unresolved;
					// control could flow to any break target,
					// probably not used in real life
					System.out.println("Unsupported 'continue' in file " + 
							this.file.getAbsolutePath() + ", line " + node.getLinenoLeft());
					throw new RuntimeException();
				}

			}
			break;
		}

		// -> T_RETURN ...
		case PhpSymbols.T_RETURN:
		{
			ParseNode secondChild = node.getChild(1);
			int secondSymbol = secondChild.getSymbol();

			if (secondSymbol == PhpSymbols.T_SEMICOLON) {
				// T_RETURN ;

				// can be ignored, but control flow has to lead directly to
				// the function's exit node
				CfgNode emptyNode = new CfgNodeEmpty();
				connect(
						emptyNode,
						((TacFunction) this.functionStack.getLast()).getCfg().getTail());

				// this Cfg must not be connected with succeeding statements,
				// so use "NO_EDGE"
				myAtts.setCfg(new Cfg(emptyNode, emptyNode, CfgEdge.NO_EDGE));

			} 

			else {
				// T_RETURN expr ;

				TacAttributes attsExpr = this.expr(secondChild);

				// get necessary function stuff
				TacFunction function = (TacFunction) this.functionStack.getLast();
				Variable retVarPlace = function.getRetVar();
				CfgNode exitNode = function.getCfg().getTail();

				//  return variable = cvar
				CfgNode cfgNode = new CfgNodeAssignSimple(
						retVarPlace, attsExpr.getPlace(), secondChild);

				// "return" statement has to lead to the function's exit node
				connect(cfgNode, exitNode);

				// expression has to be evaluated first
				connect(attsExpr.getCfg(), cfgNode);

				// this Cfg must not be connected with succeeding statements,
				// so use "NO_EDGE"
				myAtts.setCfg(new Cfg(
						attsExpr.getCfg().getHead(),
						cfgNode,
						CfgEdge.NO_EDGE));
			}                    


			break;
		}
		// -> T_GLOBAL global_var_list ;
		case PhpSymbols.T_GLOBAL:
		{
			TacAttributes atts1 = this.global_var_list(node.getChild(1));
			myAtts.setCfg(atts1.getCfg());
			break;
		}

		// -> T_STATIC static_var_list ;
		case PhpSymbols.T_STATIC:
		{
			TacAttributes atts1 = this.static_var_list(node.getChild(1));
			myAtts.setCfg(atts1.getCfg());
			break;
		}

		// -> T_ECHO echo_expr_list ;
		case PhpSymbols.T_ECHO:
		{
			TacAttributes atts1 = this.echo_expr_list(node.getChild(1));
			myAtts.setCfg(atts1.getCfg());
			break;
		}

		// -> T_INLINE_HTML
		case PhpSymbols.T_INLINE_HTML:
		{
			// static HTML output can be ignored for our analysis
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			break;
		}

		// -> expr ;
		case PhpSymbols.expr:
		{
			TacAttributes atts0 = this.expr(firstChild);
			myAtts.setCfg(atts0.getCfg());
			break;
		}

		// -> T_USE use_filename ;
		case PhpSymbols.T_USE:
		{
			// not implemented in current PHP version
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			break;
		}


		// -> T_UNSET ( variable_list ) ;
		case PhpSymbols.T_UNSET:
		{
			TacAttributes atts2 = this.variable_list(node.getChild(2));
			myAtts.setCfg(atts2.getCfg());
			break;
		}	

		// -> T_FOREACH ...
		case PhpSymbols.T_FOREACH:
		{

			if (node.getChild(2).getSymbol() == PhpSymbols.expr) {
				// -> T_FOREACH 
				//    ( expr T_AS foreach_variable foreach_optional_arg )
				//    foreach_statement

				TacAttributes attsArray = this.expr(node.getChild(2));
				this.foreachHelper(node, attsArray, myAtts);

			}

			break;
		}


		// -> T_DECLARE ( declare_list ) declare_statement
		case PhpSymbols.T_DECLARE:
		{
			// ignore declaration header
			TacAttributes attsStatement = this.declare_statement(node.getChild(4));
			myAtts.setCfg(attsStatement.getCfg());
			break;
		}
		// -> ;
		case PhpSymbols.T_SEMICOLON:
		{
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			break;
		}

		// -> T_TRY  { inner_statement_list }
		//T_CATCH ( fully_qualified_class_name T_VARIABLE ) 
		// { inner_statement_list} 
		//additional_catches

		case PhpSymbols.T_TRY:
		{
			//Mona Nashaat:
			int logId=this.tempId;
			TacAttributes atts0 = this.inner_statement_list(node.getChild(2));
			TacAttributes atts1 = this.inner_statement_list(node.getChild(10));
			TacAttributes atts2 = this.additional_catches(node.getChild(12));

			/*
			The purpose of exceptions is to break out of the normal ﬂow of control, which,
			not surprisingly, is difﬁcult to take into account in control ﬂow graphs. 
			To represent exception handling in the control ﬂow graph, the analysis would have
			to identify the program points that could result in an exception (for instance,
			each expression containing a division by a non-constant divisor could result in
			a ZeroDivisionError exception), identify the catch clauses and which exceptions
			each handles, and add edges representing the ﬂow of control from the former
			to the latter.
			However, this would add signiﬁcant complexity to the analysis, so the implementation 
			handles exceptions in a much simpler way: it assumes that since
			exceptions only occur in exceptional circumstances, they can be ignored by the
			type inference method without changing the results too much, and therefore
			catch clauses in the source code are simply not part of the control ﬂow graph.
			Figure 4.17 shows the graph for a simple try statement with one catch clause
			and a ﬁnally clause.
			 */
			connect(atts0.getCfg(),atts1.getCfg());
			connect(atts1.getCfg(), atts2.getCfg());
			myAtts.setCfg(new Cfg(
					atts0.getCfg().getHead(),
					atts2.getCfg().getTail(),
					atts2.getCfg().getTailEdgeType()));
			this.resetId(logId);
			break;


		}

		//-> T_THROW expr ;
		case PhpSymbols.T_THROW:
		{
			//Mona Nashaat

			TacAttributes attsExpr = this.expr(node.getChild(1));

			// get necessary function stuff
			TacFunction function = (TacFunction) this.functionStack.getLast();			
			CfgNode exitNode = function.getCfg().getTail();

			CfgNode cfgNodeThrow = new CfgNodeThrow(node.getChild(1));			


			// "throw" statement has to lead to the function's exit node
			connect(cfgNodeThrow, exitNode);

			// expression has to be evaluated first
			connect(attsExpr.getCfg(), cfgNodeThrow);

			// this Cfg must not be connected with succeeding statements,
			// so use "NO_EDGE"
			myAtts.setCfg(new Cfg(
					attsExpr.getCfg().getHead(),
					cfgNodeThrow,
					CfgEdge.NO_EDGE));


			break;
		}
		//->T_GOTO T_STRING T_SEMICOLON
		case PhpSymbols.T_GOTO:
		{
			//Mona Nashaat

			myAtts.setPlace(makeConstantPlace(node.getChild(1).getLexeme()));
			TacPlace goToString= myAtts.getPlace(); 

			CfgNode cfgNodeGoto = new CfgNodeGoto(goToString,node.getChild(1));			
			myAtts.setCfg(new Cfg(cfgNodeGoto, cfgNodeGoto));
			break;
		}
		//-> T_STRING T_COLON
		case PhpSymbols.T_STRING:
		{
			//Mona Nashaat	
			CfgNode cfgNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(cfgNode, cfgNode));
			myAtts.setPlace(makeConstantPlace(firstChild.getLexeme()));
			break;


		}


		}
		return myAtts;
	}

	// additional_catches *****************************************************************
	TacAttributes additional_catches(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();        
		ParseNode firstChild = node.getChild(0);      

		switch(firstChild.getSymbol()) {
		// ->  non_empty_additional_catches
		case PhpSymbols.non_empty_additional_catches:
			// case 237:
		{
			TacAttributes atts0 = this.non_empty_additional_catches(firstChild);
			myAtts.setCfg(atts0.getCfg());
			break;
		}
		/* empty */
		case PhpSymbols.T_EPSILON:
		{
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			break;
		}

		}
		return myAtts;
	}

	// non_empty_additional_catches *****************************************************************
	TacAttributes non_empty_additional_catches(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();        
		ParseNode firstChild = node.getChild(0);      

		switch(firstChild.getSymbol()) {
		// -> additional_catch
		case PhpSymbols.additional_catch:
			// case 237:
		{			
			TacAttributes atts0 = this.additional_catch(firstChild);
			myAtts.setCfg(atts0.getCfg());
			break;
		}
		// -> non_empty_additional_catches additional_catch
		case PhpSymbols.non_empty_additional_catches:

		{
			int logId = this.tempId;
			TacAttributes atts0 = this.non_empty_additional_catches(firstChild);
			TacAttributes atts1 = this.additional_catch(node.getChild(1));
			connect(atts0.getCfg(), atts1.getCfg());
			myAtts.setCfg(new Cfg(
					atts0.getCfg().getHead(),
					atts1.getCfg().getTail(),
					atts1.getCfg().getTailEdgeType()));
			this.resetId(logId);
			break;
		} 

		}
		return myAtts;
	}


	// additional_catch *****************************************************************
	TacAttributes additional_catch(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();        
		ParseNode firstChild = node.getChild(0);      

		switch(firstChild.getSymbol()) {
		// -> T_CATCH ( fully_qualified_class_name  T_VARIABLE )  { inner_statement_list }
		case PhpSymbols.T_CATCH:
			// case 237:
		{
			TacAttributes atts0 = this.inner_statement_list(node.getChild(6));
			myAtts.setCfg(atts0.getCfg());
			break;
		}
		}
		return myAtts;
	}


	// variable_list *****************************************************************
	TacAttributes variable_list(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();        
		ParseNode firstChild = node.getChild(0);      

		switch(firstChild.getSymbol()) {

		// -> variable
		case PhpSymbols.variable:
			// case 237:        	
		{               
			TacAttributes atts0 = this.variable(firstChild);
			myAtts.setCfg(atts0.getCfg());
			break;        	
		}
		// -> variable_list T_COMMA variable        	
		case PhpSymbols.variable_list:        	
		{                     
			TacAttributes atts0 = this.variable_list(firstChild);
			TacAttributes atts2 = this.variable(node.getChild(2));

			connect(atts0.getCfg(), atts2.getCfg());
			myAtts.setCfg(new Cfg(
					atts0.getCfg().getHead(),
					atts2.getCfg().getTail()));
			break;                   

		}          

		}
		return myAtts;
	}

	//Mona Nashaat
	// function_declaration_statement *****************************************************************
	TacAttributes function_declaration_statement(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();        
		ParseNode firstChild = node.getChild(0);      

		switch(firstChild.getSymbol()) {
		// -> T_FUNCTION is_reference T_STRING 
		//    ( parameter_list ) 
		//    { inner_statement_list }     
		case PhpSymbols.T_FUNCTION:
		{        	
			this.functionHelper(node, 4, 7, myAtts);
			break;
		}
		}
		return myAtts;
	}

	// class_declaration_statement *****************************************************************
	TacAttributes class_declaration_statement(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();        
		ParseNode firstChild = node.getChild(0);      

		switch(firstChild.getSymbol()) {       

		// -> class_entry_type T_STRING extends_from implements_list { class_statement_list } 
		case PhpSymbols.class_entry_type:
		{
			// collect information about the class and register
			// the TacClass in userClasses
			String className = node.getChild(1).getLexeme().toLowerCase();            
			TacClass c = new TacClass(className, node);
			TacClass existingClass = this.userClasses.get(className);
			if (existingClass == null) {
				this.extends_from(node.getChild(2), c);
				this.implements_list(node.getChild(3), c);
				this.class_entry_type(firstChild, c);
				this.userClasses.put(className, c);
				this.classStack.add(c);
				this.class_statement_list(node.getChild(5), c);
				this.classStack.removeLast();

				// if this class does not explicitly define a constructor,
				// add an empty one (makes things much easier)
				String constructorName = className + InternalStrings.methodSuffix;
				if (this.getMethod(constructorName, className) == null) {

					TacFunction constructor = this.constructorHelper(node.getChild(0), className);
					c.addMethod(constructorName, constructor);

					// add this method to a global list of methods
					TacFunction existingMethod = this.addMethod(constructorName, className, constructor);
					if (existingMethod != null) {
						throw new RuntimeException("SNH");
					}
				}

			} else {
				System.out.println("\nWarning: Duplicate class definition: " + className);
				//System.out.println("- found: " + node.getLoc());
				System.out.println("- using: " + existingClass.getLoc());
			}

			// put an empty node in place of class declarations
			// (we don't need the declaration in the resulting cfg)
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			break;

		}

		// -> interface_entry T_STRING interface_extends_list { class_statement_list }			
		case PhpSymbols.interface_entry:
		{
			// collect information about the interface and register
			// the TacInterface in userInterfaces
			String interfaceName = node.getChild(1).getLexeme().toLowerCase();

			TacInterface i = new TacInterface(interfaceName, node);
			TacInterface existingInteface = this.userInterfaces.get(interfaceName);
			if (existingInteface == null) {

				this.interface_extends_list(node.getChild(2), i);            	
				this.userInterfaces.put(interfaceName, i);
				this.interfaceStack.add(i);
				this.class_statement_list(node.getChild(4), i);
				this.interfaceStack.removeLast();

			} else {
				System.out.println("\nWarning: Duplicate interface definition: " + interfaceName);
				//System.out.println("- found: " + node.getLoc());
				System.out.println("- using: " + existingInteface.getLoc());
			}

			// put an empty node in place of class declarations
			// (we don't need the declaration in the resulting cfg)
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			break;            
		}
		}
		return myAtts;
	}

	// class_entry_type *****************************************************************
	TacAttributes class_entry_type(ParseNode node, TacClass c) {
		TacAttributes myAtts = new TacAttributes();        
		ParseNode firstChild = node.getChild(0);      

		switch(firstChild.getSymbol()) {      
		// -> T_ABSTRACT T_CLASS
		case PhpSymbols.T_ABSTRACT:
		{
			//set this class to be abstract
			c.setIsAbstrace(true);
			c.setIsFinal(false);
			break;

		}              
		// -> T_FINAL T_CLASS 			
		case PhpSymbols.T_FINAL:
		{
			//set this class to be final
			c.setIsAbstrace(false);
			c.setIsFinal(true);
			break;
		}
		// -> T_CLASS
		case PhpSymbols.T_CLASS:
		{
			c.setIsAbstrace(false);
			c.setIsFinal(false);
			break;
		}

		}
		return myAtts;
	}

	// extends_from *****************************************************************
	TacAttributes extends_from(ParseNode node, TacClass c) {
		TacAttributes myAtts = new TacAttributes();        
		ParseNode firstChild = node.getChild(0);      

		switch(firstChild.getSymbol()) {      
		// -> /* empty */
		case PhpSymbols.T_EPSILON:
		{        
			c.setSuperClassName("");
			c.setSuperClass(null);
			break;        	
		}      

		// -> T_EXTENDS fully_qualified_class_name		
		case PhpSymbols.T_EXTENDS:
		{
			//Mona Nashaat: Should I save the extend class in the TacClass Class??
			myAtts= this.fully_qualified_class_name(node.getChild(1));
			String ExtendedClassName=myAtts.getEncapsListString();
			c.setSuperClassName(ExtendedClassName);

			TacClass existingClass = this.userClasses.get(ExtendedClassName);
			if (existingClass != null) {
				c.setSuperClass(existingClass);
			}
			else
			{
				c.setSuperClass(null);
			}
		}


		}
		return myAtts;
	}

	// interface_extends_list *****************************************************************
	TacAttributes interface_extends_list(ParseNode node, TacInterface i) {
		TacAttributes myAtts = new TacAttributes();        
		ParseNode firstChild = node.getChild(0);      

		switch(firstChild.getSymbol()) {      
		// -> /* empty */
		case PhpSymbols.T_EPSILON:
		{
			break;

		}      

		// ->  T_EXTENDS interface_list	
		case PhpSymbols.interface_list:
		{
			myAtts= this.interface_list(node.getChild(1),i);
		}

		}
		return myAtts;
	}


	// implements_list *****************************************************************
	TacAttributes implements_list(ParseNode node, TacClass c) {
		TacAttributes myAtts = new TacAttributes();        
		ParseNode firstChild = node.getChild(0);      

		switch(firstChild.getSymbol()) {      
		// -> /* empty */
		case PhpSymbols.T_EPSILON:
		{        	 
			break;        	
		}      

		// -> T_IMPLEMENTS interface_list
		case PhpSymbols.T_IMPLEMENTS:
		{        	
			myAtts= this.interface_list(node.getChild(1),c);
		}

		}
		return myAtts;
	}


	// interface_list *****************************************************************
	TacAttributes interface_list(ParseNode node, TacInterface i) {
		TacAttributes myAtts = new TacAttributes();        
		ParseNode firstChild = node.getChild(0);      

		switch(firstChild.getSymbol()) {      
		// -> fully_qualified_class_name
		case PhpSymbols.fully_qualified_class_name:
		{

			TacAttributes atts0=this.fully_qualified_class_name(firstChild);
			String ImplementedInterfaceName=atts0.getEncapsListString();

			TacInterface existingInterface = this.userInterfaces.get(ImplementedInterfaceName);
			if (existingInterface != null) {
				i.addImplmentedInterface(ImplementedInterfaceName, existingInterface);          	
			}
			else
			{
				i.addImplmentedInterface(ImplementedInterfaceName, null); 
			}
			break;

		}      

		// -> interface_list T_COMMA fully_qualified_class_name
		case PhpSymbols.interface_list:
		{

			this.interface_list(firstChild, i);
			TacAttributes atts0 = this.fully_qualified_class_name(node.getChild(2));

			String ImplementedInterfaceName=atts0.getEncapsListString();

			TacInterface existingInterface = this.userInterfaces.get(ImplementedInterfaceName);
			if (existingInterface != null) {
				i.addImplmentedInterface(ImplementedInterfaceName, existingInterface);
			}
			else
			{
				i.addImplmentedInterface(ImplementedInterfaceName, null);
			}

			break;

		}

		}
		return myAtts;
	}
	// interface_list *****************************************************************
	TacAttributes interface_list(ParseNode node, TacClass c) {
		TacAttributes myAtts = new TacAttributes();        
		ParseNode firstChild = node.getChild(0);      

		switch(firstChild.getSymbol()) {   

		// -> fully_qualified_class_name        
		case PhpSymbols.fully_qualified_class_name:
		{

			TacAttributes atts0=this.fully_qualified_class_name(firstChild);
			String ImplementedInterfaceName=atts0.getEncapsListString();

			TacInterface existingInterface = this.userInterfaces.get(ImplementedInterfaceName);
			if (existingInterface != null) {
				c.addImplmentedInterface(ImplementedInterfaceName, existingInterface);          	
			}
			else
			{
				c.addImplmentedInterface(ImplementedInterfaceName, null); 
			}
			break;
		}    

		// -> interface_list T_COMMA fully_qualified_class_name
		case PhpSymbols.interface_list:
		{

			this.interface_list(firstChild, c);
			TacAttributes atts0 = this.fully_qualified_class_name(node.getChild(2));

			String ImplementedInterfaceName=atts0.getEncapsListString();

			TacInterface existingInterface = this.userInterfaces.get(ImplementedInterfaceName);
			if (existingInterface != null) {
				c.addImplmentedInterface(ImplementedInterfaceName, existingInterface);
			}
			else
			{
				c.addImplmentedInterface(ImplementedInterfaceName, null);
			}

			break;
		}

		}
		return myAtts;
	}
	// foreach_optional_arg *****************************************************************

	// - cfg
	// - place
	// can return null!
	TacAttributes foreach_optional_arg(ParseNode node) {

		if (node.getChild(0).getSymbol() == PhpSymbols.T_EPSILON) {
			// -> empty
			return null;
		} else {
			// -> T_DOUBLE_ARROW foreach_variable
			return(this.foreach_variable(node.getChild(1)));
		}
	}




	// foreach_variable *****************************************************************
	TacAttributes foreach_variable(ParseNode node) {

		TacAttributes myAtts = new TacAttributes();        
		ParseNode firstChild = node.getChild(0);      

		switch(firstChild.getSymbol()) {      
		// -> variable
		case PhpSymbols.variable:
		{
			TacAttributes attsCvar = this.variable(firstChild);
			myAtts.setCfg(attsCvar.getCfg());

			List<TacActualParam> paramList = new LinkedList<TacActualParam>();
			paramList.add(new TacActualParam(attsCvar.getPlace(), false));
			myAtts.setActualParamList(paramList);

			break;

		}      

		// ->  T_BITWISE_AND variable
		case PhpSymbols.T_BITWISE_AND:
		{

			TacAttributes attsCvar = this.variable(node.getChild(1));
			myAtts.setCfg(attsCvar.getCfg());

			List<TacActualParam> paramList = new LinkedList<TacActualParam>();
			paramList.add(new TacActualParam(attsCvar.getPlace(), true));
			myAtts.setActualParamList(paramList);

			break;
		}

		}
		return myAtts;
	}



	// for_statement *****************************************************************

	// - cfg
	TacAttributes for_statement(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> statement
		case PhpSymbols.statement:
		{
			TacAttributes atts0 = this.statement(firstChild);
			myAtts.setCfg(atts0.getCfg());
			break;
		}

		// -> : inner_statement_list T_ENDFOR ;	
		case PhpSymbols.T_COLON:
		{
			TacAttributes atts1 = this.inner_statement_list(node.getChild(1));
			myAtts.setCfg(atts1.getCfg());
			break;
		}
		}

		return myAtts;
	}


	// foreach_statement *****************************************************************

	// - cfg
	TacAttributes foreach_statement(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		if (firstChild.getSymbol() == PhpSymbols.statement) {
			// -> statement
			TacAttributes attsStatement = this.statement(firstChild);
			myAtts.setCfg(attsStatement.getCfg());
		} else {
			// -> : inner_statement_list T_ENDFOREACH ;
			TacAttributes attsList = this.inner_statement_list(node.getChild(1));
			myAtts.setCfg(attsList.getCfg());
		}

		return myAtts;
	}


	// declare_statement *****************************************************************

	// - cfg
	TacAttributes declare_statement(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> statement
		case PhpSymbols.statement:
		{
			TacAttributes atts0 = this.statement(firstChild);
			myAtts.setCfg(atts0.getCfg());
			break;
		}

		// -> : inner_statement_list T_ENDDECLARE ;
		case PhpSymbols.T_COLON:
		{
			TacAttributes atts1 = this.inner_statement_list(node.getChild(1));
			myAtts.setCfg(atts1.getCfg());
			break;
		}
		}

		return myAtts;
	}

	// switch_case_list *****************************************************************

	// - cfg
	// - defaultNode
	TacAttributes switch_case_list(ParseNode node, 
			TacPlace switchPlace, CfgNode nextTest, CfgNode nextStatement) {

		ParseNode listNode = null;
		switch(node.getChild(2).getSymbol()) {

		// -> { case_list }					
		case PhpSymbols.T_CLOSE_CURLY_BRACES:
		{
			listNode = node.getChild(1);
			break;
		}

		// -> $0 $1 case_list ...
		case PhpSymbols.case_list:
		{
			if (node.getChild(0).getSymbol() == PhpSymbols.T_OPEN_CURLY_BRACES) {
				// -> { ; case_list }				
				listNode = node.getChild(2);
			} else {
				// -> : ; case_list T_ENDSWITCH ;	
				listNode = node.getChild(2);
			}
			break;
		}

		// -> : case_list T_ENDSWITCH ;		
		case PhpSymbols.T_ENDSWITCH:
		{
			listNode = node.getChild(1);
			break;
		}

		}

		TacAttributes myAtts = this.case_list(listNode, 
				switchPlace, nextTest, nextStatement);

		return myAtts;
	}

	// case_list *****************************************************************

	// - cfg
	// - defaultNode
	TacAttributes case_list(ParseNode node,
			TacPlace switchPlace, CfgNode nextTest, CfgNode nextStatement) {

		TacAttributes myAtts = new TacAttributes();

		if (node.getChild(0).getSymbol() == PhpSymbols.T_EPSILON) {

			// -> empty
			CfgNode emptyNode = new CfgNodeEmpty();
			connect(emptyNode, nextTest);
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			myAtts.setDefaultNode(null);

		} else if (node.getChild(1).getSymbol() == PhpSymbols.T_CASE) {

			// -> case_list T_CASE expr case_separator inner_statement_list 
			TacAttributes attsExpr = this.expr(node.getChild(2));
			TacAttributes attsStatement = this.inner_statement_list(node.getChild(4));
			TacAttributes attsCaseList = this.case_list(node.getChild(0),
					switchPlace, attsExpr.getCfg().getHead(), attsStatement.getCfg().getHead());

			// buggy implementation: didn't provide valid right operand to CfgNodeIf
			/*
             CfgNode ifNode = new CfgNodeIf(
                 switchPlace, attsExpr.getPlace(), TacOperators.IS_EQUAL, node.getChild(2));

             connect(attsExpr.getCfg(), ifNode);
             connect(ifNode, attsStatement.getCfg(), CfgEdge.TRUE_EDGE);
             connect(ifNode, nextTest, CfgEdge.FALSE_EDGE);
             connect(attsStatement.getCfg(), nextStatement);
			 */

			Variable tempPlace = this.newTemp();
			CfgNode compareNode = new CfgNodeAssignBinary(
					tempPlace, switchPlace, attsExpr.getPlace(), TacOperators.IS_EQUAL, node.getChild(2));
			CfgNode ifNode = new CfgNodeIf(
					tempPlace, Constant.TRUE, TacOperators.IS_EQUAL, node.getChild(2));

			connect(attsExpr.getCfg(), compareNode);
			connect(compareNode, ifNode);
			connect(ifNode, attsStatement.getCfg(), CfgEdge.TRUE_EDGE);
			connect(ifNode, nextTest, CfgEdge.FALSE_EDGE);
			connect(attsStatement.getCfg(), nextStatement);

			// the tail doesn't matter
			myAtts.setCfg(new Cfg(
					attsCaseList.getCfg().getHead(),
					attsCaseList.getCfg().getHead()));

			myAtts.setDefaultNode(attsCaseList.getDefaultNode());


		} else {

			// -> case_list T_DEFAULT case_separator inner_statement_list 
			TacAttributes attsStatement = this.inner_statement_list(node.getChild(3));
			TacAttributes attsCaseList = this.case_list(node.getChild(0),
					switchPlace, nextTest, attsStatement.getCfg().getHead());

			connect(attsStatement.getCfg(), nextStatement);

			// the tail doesn't matter
			myAtts.setCfg(new Cfg(
					attsCaseList.getCfg().getHead(),
					attsCaseList.getCfg().getHead()));

			myAtts.setDefaultNode(attsStatement.getCfg().getHead());

		}

		return myAtts;
	}


	// while_statement *****************************************************************

	// - cfg
	TacAttributes while_statement(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> statement
		case PhpSymbols.statement:
		{
			TacAttributes atts0 = this.statement(firstChild);
			myAtts.setCfg(atts0.getCfg());
			break;
		}

		// -> : inner_statement_list T_ENDWHILE ;
		case PhpSymbols.T_COLON:
		{
			TacAttributes atts1 = this.inner_statement_list(node.getChild(1));
			myAtts.setCfg(atts1.getCfg());
			break;
		}

		}

		return myAtts;
	}

	// elseif_list *****************************************************************

	// - cfg
	TacAttributes elseif_list(ParseNode node, CfgNode trueSucc, CfgNode falseSucc) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> empty       
		case PhpSymbols.T_EPSILON:
		{
			CfgNode emptyNode = new CfgNodeEmpty();
			connect(emptyNode, falseSucc);
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			break;
		}

		// -> elseif_list T_ELSEIF ( expr ) statement 
		case PhpSymbols.elseif_list:
		{
			int logId = this.tempId;
			TacAttributes attsExpr = this.expr(node.getChild(3));
			this.resetId(logId);
			TacAttributes attsElif = this.elseif_list(firstChild, trueSucc, attsExpr.getCfg().getHead());
			TacAttributes attsStatement = this.statement(node.getChild(5));

			CfgNode ifNode = new CfgNodeIf(
					attsExpr.getPlace(), Constant.TRUE, TacOperators.IS_EQUAL, node.getChild(3));

			connect(attsExpr.getCfg(), ifNode);
			connect(ifNode, falseSucc, CfgEdge.FALSE_EDGE);
			connect(ifNode, attsStatement.getCfg(), CfgEdge.TRUE_EDGE);
			connect(attsStatement.getCfg(), trueSucc);

			myAtts.setCfg(new Cfg(
					attsElif.getCfg().getHead(),
					attsStatement.getCfg().getTail(),
					attsStatement.getCfg().getTailEdgeType()));

			break;
		}
		}

		return myAtts;
	}

	// new_elseif_list *****************************************************************

	// - cfg
	TacAttributes new_elseif_list(ParseNode node, CfgNode trueSucc, CfgNode falseSucc) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> empty

		case PhpSymbols.T_EPSILON:
		{
			CfgNode emptyNode = new CfgNodeEmpty();
			connect(emptyNode, falseSucc);
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			break;
		}

		// -> new_elseif_list T_ELSEIF ( expr ) : inner_statement_list 
		case PhpSymbols.new_elseif_list:
		{
			int logId = this.tempId;
			TacAttributes attsExpr = this.expr(node.getChild(3));
			this.resetId(logId);
			TacAttributes attsElif = this.new_elseif_list(
					firstChild, trueSucc, attsExpr.getCfg().getHead());
			TacAttributes attsStatement = 
					this.inner_statement_list(node.getChild(6));

			CfgNode ifNode = new CfgNodeIf(
					attsExpr.getPlace(), Constant.TRUE, TacOperators.IS_EQUAL,
					node.getChild(3));

			connect(attsExpr.getCfg(), ifNode);
			connect(ifNode, falseSucc, CfgEdge.FALSE_EDGE);
			connect(ifNode, attsStatement.getCfg(), CfgEdge.TRUE_EDGE);
			connect(attsStatement.getCfg(), trueSucc);

			myAtts.setCfg(new Cfg(
					attsElif.getCfg().getHead(),
					attsStatement.getCfg().getTail(),
					attsStatement.getCfg().getTailEdgeType()));

			break;
		}
		}

		return myAtts;
	}

	// else_single *****************************************************************

	// - cfg
	TacAttributes else_single(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> empty
		case PhpSymbols.T_EPSILON:
		{
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			break;
		}

		// -> T_ELSE statement
		case PhpSymbols.T_ELSE:
		{
			TacAttributes attsStatement = this.statement(node.getChild(1));
			myAtts.setCfg(attsStatement.getCfg());
			break;
		}
		}

		return myAtts;
	}

	// new_else_single *****************************************************************

	// - cfg
	TacAttributes new_else_single(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> empty

		case PhpSymbols.T_EPSILON:
		{
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			break;
		}

		// -> T_ELSE : inner_statement_list
		case PhpSymbols.T_ELSE:
		{
			TacAttributes attsStatement = 
					this.inner_statement_list(node.getChild(2));
			myAtts.setCfg(attsStatement.getCfg());
			break;
		}
		}

		return myAtts;
	}

	// parameter_list *****************************************************************

	// - paramList
	TacAttributes parameter_list(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		if (firstChild.getSymbol() == PhpSymbols.non_empty_parameter_list) {
			// -> non_empty_parameter_list
			myAtts = this.non_empty_parameter_list(firstChild);
		} else {
			// -> empty
			myAtts.setFormalParamList(new LinkedList<TacFormalParam>());
		}

		return myAtts;
	}


	// non_empty_parameter_list *****************************************************************

	// - paramList
	TacAttributes non_empty_parameter_list(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		ParseNode secondChild = node.getChild(1);
		switch(secondChild.getSymbol()) {

		// -> optional_class_type T_VARIABLE
		// -> optional_class_type T_VARIABLE T_ASSIGN static_expr
		case PhpSymbols.T_VARIABLE:
		{
			if (node.getNumChildren() == 2) {
				// -> optional_class_type T_VARIABLE


				TacPlace paramPlace = this.makePlace(secondChild.getLexeme());
				TacFormalParam param = new TacFormalParam(paramPlace.getVariable());
				//Get Custom Class type [if found!]
				this.optional_class_type(firstChild, param);
				List<TacFormalParam> paramList = new LinkedList<TacFormalParam>();				
				paramList.add(param);
				myAtts.setFormalParamList(paramList);
			} else {
				// -> optional_class_type T_VARIABLE = static_expr

				TacAttributes atts2 = this.static_expr(node.getChild(3));
				Variable paramPlace = this.makePlace(secondChild.getLexeme());

				CfgNode cfgNode = 
						new CfgNodeAssignSimple(paramPlace, atts2.getPlace(), node.getChild(3));
				connect(atts2.getCfg(), cfgNode);
				Cfg defaultCfg = new Cfg(
						atts2.getCfg().getHead(), cfgNode);
				this.optimize(defaultCfg);
				TacFormalParam param = new TacFormalParam(
						paramPlace.getVariable(), true, defaultCfg);
				//Get Custom Class type [if found!]
				this.optional_class_type(firstChild, param);				
				List<TacFormalParam> paramList = new LinkedList<TacFormalParam>();
				paramList.add(param);
				myAtts.setFormalParamList(paramList);
			}

			break;
		}

		// -> optional_class_type T_BITWISE_AND T_VARIABLE	
		// -> optional_class_type T_BITWISE_AND T_VARIABLE = static_expr
		case PhpSymbols.T_BITWISE_AND:
		{if (node.getNumChildren() == 3) {
			// -> optional_class_type T_BITWISE_AND T_VARIABLE	

			TacPlace paramPlace = this.makePlace(node.getChild(2).getLexeme());
			TacFormalParam param = new TacFormalParam(paramPlace.getVariable(), true);
			//Get Custom Class type [if found!]
			this.optional_class_type(firstChild, param);			
			List<TacFormalParam> paramList = new LinkedList<TacFormalParam>();
			paramList.add(param);
			myAtts.setFormalParamList(paramList);                



		}
		else
		{ // -> optional_class_type T_BITWISE_AND T_VARIABLE = static_expr

			TacAttributes atts2 = this.static_expr(node.getChild(4));
			Variable paramPlace = this.makePlace(node.getChild(2).getLexeme());

			CfgNode cfgNode = 
					new CfgNodeAssignSimple(paramPlace, atts2.getPlace(), node.getChild(4));
			connect(atts2.getCfg(), cfgNode);
			Cfg defaultCfg = new Cfg(
					atts2.getCfg().getHead(), cfgNode);
			this.optimize(defaultCfg);
			TacFormalParam param = new TacFormalParam(
					paramPlace.getVariable(), true, defaultCfg);

			param.setIsReference(true);
			//Get Custom Class type [if found!]
			this.optional_class_type(firstChild, param);			

			List<TacFormalParam> paramList = new LinkedList<TacFormalParam>();
			paramList.add(param);
			myAtts.setFormalParamList(paramList);
		}

		break;
		}

		// -> non_empty_parameter_list ...
		case PhpSymbols.T_COMMA:
		{
			switch(node.getChild(3).getSymbol()) {

			//-> non_empty_parameter_list , optional_class_type T_VARIABLE....
			case PhpSymbols.T_VARIABLE:
			{
				if (node.getNumChildren() == 4) {
					// -> non_empty_parameter_list , optional_class_type T_VARIABLE

					TacPlace paramPlace = this.makePlace(node.getChild(3).getLexeme());
					TacFormalParam param = new TacFormalParam(paramPlace.getVariable());
					TacAttributes attsList = this.non_empty_parameter_list(firstChild);

					//Get Custom Class type [if found!]
					this.optional_class_type(node.getChild(2), param);			


					List<TacFormalParam> paramList = attsList.getFormalParamList();
					paramList.add(param);
					myAtts.setFormalParamList(paramList);
				} else {
					// -> non_empty_parameter_list , optional_class_type T_VARIABLE = static_expr
					TacAttributes attsList = this.non_empty_parameter_list(firstChild);

					TacAttributes attsScalar = this.static_expr(node.getChild(5));
					Variable paramPlace = this.makePlace(node.getChild(3).getLexeme());

					CfgNode cfgNode = 
							new CfgNodeAssignSimple(paramPlace, attsScalar.getPlace(), node.getChild(5));
					connect(attsScalar.getCfg(), cfgNode);
					Cfg defaultCfg = new Cfg(
							attsScalar.getCfg().getHead(), cfgNode);
					this.optimize(defaultCfg);
					TacFormalParam param = new TacFormalParam(
							paramPlace.getVariable(), true, defaultCfg);
					//Get Custom Class type [if found!]
					this.optional_class_type(node.getChild(2), param);			

					List<TacFormalParam> paramList = attsList.getFormalParamList();
					paramList.add(param);
					myAtts.setFormalParamList(paramList);
				}
				break;
			}


			// -> non_empty_parameter_list , optional_class_type & T_VARIABLE
			case PhpSymbols.T_BITWISE_AND:
			{
				// -> non_empty_parameter_list , optional_class_type & T_VARIABLE
				if (node.getNumChildren() == 5) {


					TacAttributes attsList = this.non_empty_parameter_list(firstChild);



					TacPlace paramPlace = this.makePlace(node.getChild(4).getLexeme());
					TacFormalParam param = new TacFormalParam(paramPlace.getVariable(), true);

					//Get Custom Class type [if found!]
					this.optional_class_type(node.getChild(2), param);			


					List<TacFormalParam> paramList = attsList.getFormalParamList();
					paramList.add(param);
					myAtts.setFormalParamList(paramList);

				}
				// ->  non_empty_parameter_list , optional_class_type T_BITWISE_AND T_VARIABLE	 = static_expr
				else
				{

					TacAttributes attsList = this.non_empty_parameter_list(firstChild);                              

					TacAttributes attsScalar = this.static_expr(node.getChild(6));
					Variable paramPlace = this.makePlace(node.getChild(4).getLexeme());

					CfgNode cfgNode = 
							new CfgNodeAssignSimple(paramPlace, attsScalar.getPlace(), node.getChild(6));
					connect(attsScalar.getCfg(), cfgNode);
					Cfg defaultCfg = new Cfg(
							attsScalar.getCfg().getHead(), cfgNode);
					this.optimize(defaultCfg);
					TacFormalParam param = new TacFormalParam(
							paramPlace.getVariable(), true, defaultCfg);

					//Get Custom Class type [if found!]
					this.optional_class_type(node.getChild(2), param);			

					List<TacFormalParam> paramList = attsList.getFormalParamList();
					paramList.add(param);
					myAtts.setFormalParamList(paramList);


				}
				break;
			}


			}
			break;
		}
		}

		return myAtts;
	}

	// optional_class_type *****************************************************************

	// - cfg
	TacAttributes optional_class_type(ParseNode node,TacFormalParam Param) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> /* empty */
		case PhpSymbols.T_EPSILON:
		{
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			break;
		}

		// -> fully_qualified_class_name
		case PhpSymbols.fully_qualified_class_name:
		{			
			myAtts=this.fully_qualified_class_name(node.getChild(0));
			String CustomClass=myAtts.getEncapsListString();
			Param.getVariable().SetisCustomObject(true);
			Param.getVariable().SetCustomClass(CustomClass);
			break;
		}

		// -> T_ARRAY
		case PhpSymbols.T_ARRAY:
		{
			Param.getVariable().setIsArray(true);
			Param.getVariable().SetisCustomObject(false);
			break;
		}
		}

		return myAtts;
	}

	// function_call_parameter_list *****************************************************************

	// - cfg
	// - paramList
	TacAttributes function_call_parameter_list(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		if (firstChild.getSymbol() == PhpSymbols.non_empty_function_call_parameter_list) {

			// -> non_empty_function_call_parameter_list

			TacAttributes attsList = this.non_empty_function_call_parameter_list(firstChild);
			myAtts.setCfg(attsList.getCfg());
			myAtts.setActualParamList(attsList.getActualParamList());

		} else {

			// -> empty 
			CfgNode cfgNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(cfgNode, cfgNode));
			List<TacActualParam> ll = new LinkedList<TacActualParam>();
			myAtts.setActualParamList(ll);
		}

		return myAtts;
	}



	// non_empty_function_call_parameter_list *****************************************************************

	// - cfg
	// - paramList
	TacAttributes non_empty_function_call_parameter_list(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> expr

		case PhpSymbols.expr:
		{

			TacAttributes attsExpr = this.expr(firstChild);
			myAtts.setCfg(attsExpr.getCfg());

			List<TacActualParam> paramList = new LinkedList<TacActualParam>();
			paramList.add(new TacActualParam(attsExpr.getPlace(), false));
			myAtts.setActualParamList(paramList);

			break;
		}

		//->& variable
		case PhpSymbols.T_BITWISE_AND:
		{

			TacAttributes attsCvar = this.variable(node.getChild(1));
			myAtts.setCfg(attsCvar.getCfg());

			List<TacActualParam> paramList = new LinkedList<TacActualParam>();
			paramList.add(new TacActualParam(attsCvar.getPlace(), true));
			myAtts.setActualParamList(paramList);

			break;
		}

		// -> non_empty_function_call_parameter_list ...
		case PhpSymbols.non_empty_function_call_parameter_list:
		{
			ParseNode thirdChild = node.getChild(2);
			int thirdSymbol = thirdChild.getSymbol();
			// -> non_empty_function_call_parameter_list , expr 
			if (thirdSymbol == PhpSymbols.expr) {

				// -> non_empty_function_call_parameter_list , expr	

				TacAttributes attsList = 
						this.non_empty_function_call_parameter_list(firstChild);
				TacAttributes attsExpr = this.expr(thirdChild);

				connect(attsList.getCfg(), attsExpr.getCfg());

				myAtts.setCfg(new Cfg(
						attsList.getCfg().getHead(),
						attsExpr.getCfg().getTail()));

				List<TacActualParam> paramList = attsList.getActualParamList();
				paramList.add(new TacActualParam(attsExpr.getPlace(), false));
				myAtts.setActualParamList(paramList);
				//Mona Nashaat: change cvar to variable
			} 

			else {

				// -> non_empty_function_call_parameter_list , & variable

				TacAttributes attsList = 
						this.non_empty_function_call_parameter_list(firstChild);
				TacAttributes attsCvar = this.variable(node.getChild(3));

				connect(attsList.getCfg(), attsCvar.getCfg());

				myAtts.setCfg(new Cfg(
						attsList.getCfg().getHead(),
						attsCvar.getCfg().getTail()));

				List<TacActualParam> paramList = attsList.getActualParamList();
				paramList.add(new TacActualParam(attsCvar.getPlace(), true));
				myAtts.setActualParamList(paramList);
			}

			break;
		}
		}

		return myAtts;
	}


	// global_var_list *****************************************************************

	// - cfg
	TacAttributes global_var_list(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);

		if (firstChild.getSymbol() == PhpSymbols.global_var_list) {
			// -> global_var_list , global_var

			TacAttributes atts0 = this.global_var_list(firstChild);
			TacAttributes atts2 = this.global_var(node.getChild(2));
			connect(atts0.getCfg(), atts2.getCfg());
			myAtts.setCfg(new Cfg(
					atts0.getCfg().getHead(),
					atts2.getCfg().getTail()));

		} else {
			// -> global_var
			TacAttributes atts0 = this.global_var(firstChild);
			myAtts.setCfg(atts0.getCfg());
		}

		return myAtts;
	}

	// global_var **********************************************************************

	// - cfg
	TacAttributes global_var(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		if (node.getChild(0).getSymbol() == PhpSymbols.T_VARIABLE) {
			// -> T_VARIABLE

			String varLex = node.getChild(0).getLexeme();
			TacPlace varPlace = makePlace(varLex);
			// there also has to be a global variable with the same name 
			makePlace(varLex, this.mainSymbolTable);
			CfgNode cfgNode = new CfgNodeGlobal(varPlace, node);
			myAtts.setCfg(new Cfg(cfgNode, cfgNode));                    

		} 

		else if (node.getChild(1).getSymbol() == PhpSymbols.variable) {
			// -> $ variable
			// ex: global $$a
			TacAttributes attsCvar = this.variable(node.getChild(1));
			TacPlace cvarPlace = attsCvar.getPlace();
			TacPlace varPlace = makePlace("${" + cvarPlace.toString() + "}");
			varPlace.getVariable().setDependsOn(cvarPlace);
			CfgNode cfgNode = new CfgNodeGlobal(varPlace, node);

			connect(attsCvar.getCfg(), cfgNode);
			myAtts.setCfg(new Cfg(
					attsCvar.getCfg().getHead(), 
					cfgNode));

		} 
		else {
			// -> $ { expr }
			// ex: global ${$a} or something more complicated
			TacAttributes attsExpr = this.expr(node.getChild(2));
			TacPlace varPlace = this.exprVarHelper(attsExpr.getPlace());
			CfgNode cfgNode = new CfgNodeGlobal(varPlace, node);
			connect(attsExpr.getCfg(), cfgNode);
			myAtts.setCfg(new Cfg(attsExpr.getCfg().getHead(), cfgNode));                    
		}

		return myAtts;
	}



	// static_var_list *****************************************************************

	// - cfg
	TacAttributes static_var_list(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);

		if (firstChild.getSymbol() == PhpSymbols.static_var_list) {
			if (node.getNumChildren() == 3) {
				// -> static_var_list , T_VARIABLE

				TacAttributes atts0 = this.static_var_list(firstChild);

				TacPlace varPlace = makePlace(node.getChild(2).getLexeme());
				CfgNode cfgNode = new CfgNodeStatic(varPlace, node);

				connect(atts0.getCfg(), cfgNode);

				myAtts.setCfg(new Cfg(
						atts0.getCfg().getHead(),
						cfgNode));                    

			} else {
				// -> static_var_list , T_VARIABLE = static_expr

				TacAttributes atts0 = this.static_var_list(firstChild);
				TacAttributes atts4 = this.static_expr(node.getChild(4));

				TacPlace varPlace = makePlace(node.getChild(2).getLexeme());
				CfgNode cfgNode = new CfgNodeStatic(varPlace, atts4.getPlace(), node);

				connect(atts0.getCfg(), atts4.getCfg());
				connect(atts4.getCfg(), cfgNode);

				myAtts.setCfg(new Cfg(
						atts0.getCfg().getHead(),
						cfgNode));                    
			}
		} else {
			if (node.getNumChildren() == 1) {
				// -> T_VARIABLE

				TacPlace varPlace = makePlace(firstChild.getLexeme());
				CfgNode cfgNode = new CfgNodeStatic(varPlace, node);
				myAtts.setCfg(new Cfg(cfgNode, cfgNode));                    

			} else {
				// -> T_VARIABLE = static_expr

				TacAttributes atts2 = this.static_expr(node.getChild(2));

				TacPlace varPlace = makePlace(firstChild.getLexeme());
				CfgNode cfgNode = new CfgNodeStatic(varPlace, atts2.getPlace(), node);

				connect(atts2.getCfg(), cfgNode);

				myAtts.setCfg(new Cfg(
						atts2.getCfg().getHead(),
						cfgNode));                    
			}
		}

		return myAtts;
	}



	// class_statement_list ************************************************************

	// does not return anything in the attributes, only collects information
	// about the class and writes it into the provided TacClass
	TacAttributes class_statement_list(ParseNode node, TacClass c) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> class_statement_list class_statement
		case PhpSymbols.class_statement_list:
		{
			int logId = this.tempId;
			this.class_statement_list(firstChild, c);
			this.class_statement(node.getChild(1), c);
			this.resetId(logId);
			break;
		}

		// -> empty
		case PhpSymbols.T_EPSILON:
		{
			// nothing to do here
			break;
		}
		}

		return myAtts;
	}

	// class_statement_list ************************************************************

	// does not return anything in the attributes, only collects information
	// about the Interface and writes it into the provided TacInterface
	TacAttributes class_statement_list(ParseNode node, TacInterface i) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> class_statement_list class_statement
		case PhpSymbols.class_statement_list:
		{
			int logId = this.tempId;
			this.class_statement_list(firstChild, i);
			this.class_statement(node.getChild(1), i);
			this.resetId(logId);
			break;
		}

		// -> empty
		case PhpSymbols.T_EPSILON:
		{
			// nothing to do here
			break;
		}
		}

		return myAtts;
	}

	// class_statement *****************************************************************

	// - (nothing)
	TacAttributes class_statement(ParseNode node, TacInterface i) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> variable_modifiers  class_variable_declaration ; 
		case PhpSymbols.variable_modifiers:
		{
			// Mona Nashaat
			this.class_variable_decleration(node.getChild(1), i,node.getChild(0));
			break;
		}
		// -> method_modifiers T_FUNCTION is_reference T_STRING  ( 
		//parameter_list ) method_body
		case PhpSymbols.method_modifiers:
		{
			// Mona Nashaat
			// create a TacFunction for this method and add it to the class
			String methodName = 
					node.getChild(3).getLexeme().toLowerCase() + InternalStrings.methodSuffix;
			TacFunction method = this.methodHelper(node, 5, 7, methodName,true);
			this.method_modifiers(node.getChild(0),method);
			i.addMethod(methodName, method);

			// add this method to a global list of methods;
			// warn in case of duplicate methods names (happens if
			// there are two classes with the same name and the same method,
			// e.g., in case of conditional class definition)
			TacFunction existingMethod = this.addMethod(
					methodName, this.interfaceStack.getLast().getName(), method);
			if (existingMethod != null) {
				System.out.println("\nWarning: Duplicate method definition: " + methodName);
				System.out.println("- found: " + existingMethod.getLoc());
				System.out.println("- using: " + method.getLoc());
			}


			break;
		}

		// -> class_constant_declaration T_SEMICOLON

		case PhpSymbols.class_constant_declaration:
		{

			this.class_constant_declaration(node.getChild(1), i);
			break;


		}

		}

		return myAtts;
	}
	// class_statement *****************************************************************

	// - (nothing)
	TacAttributes class_statement(ParseNode node, TacClass c) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> variable_modifiers  class_variable_declaration T_SEMICOLON 

		case PhpSymbols.variable_modifiers:
		{
			// Mona Nashaat :
			this.class_variable_decleration(node.getChild(1), c, node.getChild(0));
			break;
		}


		// -> method_modifiers T_FUNCTION is_reference T_STRING  T_OPEN_BRACES 
		//parameter_list T_CLOSE_BRACES method_body


		case PhpSymbols.method_modifiers:
		{
			// Mona Nashaat : What to de with method_modifiers??


			// create a TacFunction for this method and add it to the class
			String methodName = 
					node.getChild(3).getLexeme().toLowerCase() + InternalStrings.methodSuffix;
			TacFunction method = this.methodHelper(node, 5, 7, methodName,false);
			this.method_modifiers(node.getChild(0),method);
			c.addMethod(methodName, method);

			// add this method to a global list of methods;
			// warn in case of duplicate methods names (happens if
			// there are two classes with the same name and the same method,
			// e.g., in case of conditional class definition)
			TacFunction existingMethod = this.addMethod(
					methodName, this.classStack.getLast().getName(), method);
			if (existingMethod != null) {
				System.out.println("\nWarning: Duplicate method definition: " + methodName);
				System.out.println("- found: " + existingMethod.getLoc());
				System.out.println("- using: " + method.getLoc());
			}


			break;
		}

		// -> class_constant_declaration T_SEMICOLON

		case PhpSymbols.class_constant_declaration:
		{
			// LATER
			this.class_constant_declaration(node.getChild(0), c);
			break;
			//   throw new RuntimeException("not yet");

		}

		}

		return myAtts;
	}

	// method_body *****************************************************************

	// - cfg
	TacAttributes method_body(ParseNode node, TacFunction method) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> T_SEMICOLON /* abstract method */

		case PhpSymbols.T_SEMICOLON:
		{
			// Mona Nashaat : Set this mehtod as Abstract?
			break;
		}

		// -> T_OPEN_CURLY_BRACES inner_statement_list T_CLOSE_CURLY_BRACES 
		case PhpSymbols.T_OPEN_CURLY_BRACES:
		{
			TacAttributes atts0 =this.inner_statement_list(node.getChild(1));
			myAtts.setCfg(atts0.getCfg());
			myAtts.setPlace(atts0.getPlace());

			break;
		}
		}
		return myAtts;
	}


	// variable_modifiers *****************************************************************

	void variable_modifiers(ParseNode node, Variable var) {


		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> non_empty_member_modifiers

		case PhpSymbols.non_empty_member_modifiers:
		{
			this.non_empty_member_modifiers(firstChild,var);
			break;


		}

		// -> T_VAR
		case PhpSymbols.T_VAR:
		{

			break;
		}
		}

	}


	// method_modifiers *****************************************************************

	// - cfg
	void method_modifiers(ParseNode node,TacFunction method) {

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// ->/* empty */ 

		case PhpSymbols.T_EPSILON:
		{

			// nothing to do here
			break;
		}

		// -> non_empty_member_modifiers
		case PhpSymbols.non_empty_member_modifiers:
		{
			this.non_empty_member_modifiers(firstChild,method);

			break;


		}
		}

	}

	// non_empty_member_modifiers *****************************************************************

	// - cfg
	void non_empty_member_modifiers(ParseNode node,TacFunction method) {

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> member_modifier

		case PhpSymbols.member_modifier:
		{
			this.member_modifier(firstChild,method);
			break;
		}

		// -> non_empty_member_modifiers member_modifier
		case PhpSymbols.non_empty_member_modifiers:
		{

			this.non_empty_member_modifiers(firstChild,method);
			this.member_modifier(node.getChild(1),method);

			break;

		}
		}

	}

	// non_empty_member_modifiers *****************************************************************

	// - cfg
	void non_empty_member_modifiers(ParseNode node,Variable Var) {

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> member_modifier

		case PhpSymbols.member_modifier:
		{
			this.member_modifier(firstChild,Var);
			break;
		}

		// -> non_empty_member_modifiers member_modifier
		case PhpSymbols.non_empty_member_modifiers:
		{

			this.non_empty_member_modifiers(firstChild,Var);
			this.member_modifier(node.getChild(1),Var);

			break;

		}
		}

	}

	// member_modifier *****************************************************************


	void member_modifier(ParseNode node,TacFunction method) {

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> T_PUBLIC

		case PhpSymbols.T_PUBLIC:
		{

			//Mona Nashaat:
			method.setAccessModifier("public");

			break;

		}

		// -> T_PROTECTED
		case PhpSymbols.T_PROTECTED:
		{

			//Mona Nashaat

			method.setAccessModifier("protected");
			break;

		}
		// -> T_PRIVATE
		case PhpSymbols.T_PRIVATE:
		{

			//Mona Nashaat
			method.setAccessModifier("private");
			break;

		}

		// -> T_STATIC
		case PhpSymbols.T_STATIC:
		{

			//Mona Nashaat
			method.SetStatic(true);
			break;

		}
		// -> T_ABSTRACT
		case PhpSymbols.T_ABSTRACT:
		{

			//Mona Nashaat
			method.SetAbstract(true);
			method.SetFinal(false);
			break;

		}

		// -> T_FINAL
		case PhpSymbols.T_FINAL:
		{

			//Mona Nashaat
			method.SetAbstract(false);
			method.SetFinal(true);			
			break;

		}
		}

	}

	// member_modifier *****************************************************************


	void member_modifier(ParseNode node,Variable Var) {

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> T_PUBLIC

		case PhpSymbols.T_PUBLIC:
		{

			//Mona Nashaat:
			Var.setAccessModifier("public");

			break;

		}

		// -> T_PROTECTED
		case PhpSymbols.T_PROTECTED:
		{

			//Mona Nashaat

			Var.setAccessModifier("protected");
			break;

		}
		// -> T_PRIVATE
		case PhpSymbols.T_PRIVATE:
		{

			//Mona Nashaat
			Var.setAccessModifier("private");
			break;

		}

		// -> T_STATIC
		case PhpSymbols.T_STATIC:
		{

			//Mona Nashaat
			Var.SetStatic(true);
			break;

		}
		// -> T_ABSTRACT
		case PhpSymbols.T_ABSTRACT:
		{

			//Mona Nashaat
			Var.SetAbstract(true);
			Var.SetFinal(false);
			break;

		}

		// -> T_FINAL
		case PhpSymbols.T_FINAL:
		{

			//Mona Nashaat
			Var.SetAbstract(false);
			Var.SetFinal(true);			
			break;

		}
		}

	}


	// class_variable_declaration *****************************************************************
	TacAttributes class_variable_decleration(ParseNode node, TacClass c, ParseNode Modifiers) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> class_variable_decleration ...

		case PhpSymbols.class_variable_declaration:
		{
			if (node.getNumChildren() == 3) {
				// -> class_variable_decleration T_COMMA T_VARIABLE
				this.class_variable_decleration(firstChild, c,Modifiers);

				Variable var = this.newTemp();
				this.variable_modifiers(Modifiers, var);
				CfgNode cfgNode = new CfgNodeAssignSimple(var, this.constantsTable.getConstant("NULL"), node);
				Cfg nullCfg = new Cfg(cfgNode, cfgNode);
				c.addMember(node.getChild(2).getLexeme(), nullCfg, var);
			} else {
				// -> class_variable_decleration T_COMMA T_VARIABLE T_ASSIGN static_expr
				this.class_variable_decleration(firstChild, c,Modifiers);

				TacAttributes atts4 = this.static_expr(node.getChild(4));
				c.addMember(node.getChild(2).getLexeme(), atts4.getCfg(), atts4.getPlace());
			}
			break;
		}

		// -> T_VARIABLE ...
		case PhpSymbols.T_VARIABLE:
		{
			if (node.getNumChildren() == 1) {
				// -> T_VARIABLE
				// create a one-node cfg that assigns NULL to a temporary variable,
				// and use this cfg as initializer for the member variable
				Variable var = this.newTemp();
				this.variable_modifiers(Modifiers, var);
				CfgNode cfgNode = new CfgNodeAssignSimple(var, this.constantsTable.getConstant("NULL"), node);
				Cfg nullCfg = new Cfg(cfgNode, cfgNode);
				c.addMember(firstChild.getLexeme(), nullCfg, var);
			} else {
				// -> T_VARIABLE T_ASSIGN static_expr
				TacAttributes atts2 = this.static_expr(node.getChild(2));
				c.addMember(firstChild.getLexeme(), atts2.getCfg(), atts2.getPlace());
			}
			break;
		}

		}

		return myAtts;
	}

	// class_variable_declaration *****************************************************************
	TacAttributes class_variable_decleration(ParseNode node, TacInterface i, ParseNode Modifiers) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> class_variable_decleration ...

		case PhpSymbols.class_variable_declaration:
		{
			if (node.getNumChildren() == 3) {
				// -> class_variable_decleration T_COMMA T_VARIABLE
				this.class_variable_decleration(firstChild, i,Modifiers);
				Variable var = this.newTemp();
				this.variable_modifiers(Modifiers, var);
				CfgNode cfgNode = new CfgNodeAssignSimple(var, this.constantsTable.getConstant("NULL"), node);
				Cfg nullCfg = new Cfg(cfgNode, cfgNode);
				i.addMember(node.getChild(2).getLexeme(), nullCfg, var);
			} else {
				// -> class_variable_decleration T_COMMA T_VARIABLE T_ASSIGN static_expr
				this.class_variable_decleration(firstChild, i,Modifiers);
				TacAttributes atts4 = this.static_expr(node.getChild(4));
				i.addMember(node.getChild(2).getLexeme(), atts4.getCfg(), atts4.getPlace());
			}
			break;
		}

		// -> T_VARIABLE ...
		case PhpSymbols.T_VARIABLE:
		{
			if (node.getNumChildren() == 1) {
				// -> T_VARIABLE
				// create a one-node cfg that assigns NULL to a temporary variable,
				// and use this cfg as initializer for the member variable
				Variable var = this.newTemp();
				this.variable_modifiers(Modifiers, var);
				CfgNode cfgNode = new CfgNodeAssignSimple(var, this.constantsTable.getConstant("NULL"), node);
				Cfg nullCfg = new Cfg(cfgNode, cfgNode);
				i.addMember(firstChild.getLexeme(), nullCfg, var);
			} else {
				// -> T_VARIABLE T_ASSIGN static_expr
				TacAttributes atts2 = this.static_expr(node.getChild(2));
				i.addMember(firstChild.getLexeme(), atts2.getCfg(), atts2.getPlace());
			}
			break;
		}

		}

		return myAtts;
	}

	// class_constant_declaration *****************************************************************
	TacAttributes class_constant_declaration(ParseNode node, TacClass c) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> class_constant_declaration T_COMMA T_STRING T_ASSIGN static_expr 

		case PhpSymbols.class_constant_declaration:
		{
			this.class_constant_declaration(firstChild, c);

			TacAttributes atts4 = this.static_expr(node.getChild(4));
			c.addMember(node.getChild(2).getLexeme(), atts4.getCfg(), atts4.getPlace());


			break;
		}

		// -> T_CONST T_STRING T_ASSIGN static_expr
		case PhpSymbols.T_CONST:
		{
			TacAttributes atts2 = this.static_expr(node.getChild(3));
			c.addMember(node.getChild(1).getLexeme(), atts2.getCfg(), atts2.getPlace());
			break;
		}

		}

		return myAtts;
	}

	// class_constant_declaration *****************************************************************
	TacAttributes class_constant_declaration(ParseNode node, TacInterface c) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> class_constant_declaration T_COMMA T_STRING T_ASSIGN static_expr 

		case PhpSymbols.class_constant_declaration:
		{
			this.class_constant_declaration(firstChild, c);

			TacAttributes atts4 = this.static_expr(node.getChild(4));
			c.addMember(node.getChild(2).getLexeme(), atts4.getCfg(), atts4.getPlace());


			break;
		}

		// -> T_CONST T_STRING T_ASSIGN static_expr
		case PhpSymbols.T_CONST:
		{
			TacAttributes atts2 = this.static_expr(node.getChild(3));
			c.addMember(node.getChild(1).getLexeme(), atts2.getCfg(), atts2.getPlace());
			break;
		}

		}

		return myAtts;
	}


	// echo_expr_list *****************************************************************

	// - cfg
	TacAttributes echo_expr_list(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> echo_expr_list , expr 
		case PhpSymbols.echo_expr_list:
		{
			TacAttributes attsList = this.echo_expr_list(firstChild);
			TacAttributes attsExpr = this.expr(node.getChild(2));

			CfgNode cfgNode = new CfgNodeEcho(attsExpr.getPlace(), node);

			connect(attsList.getCfg(), attsExpr.getCfg());
			connect(attsExpr.getCfg(), cfgNode);

			myAtts.setCfg(new Cfg(
					attsList.getCfg().getHead(),
					cfgNode));

			break;
		}

		// -> expr					
		case PhpSymbols.expr:
		{
			TacAttributes atts0 = this.expr(firstChild);
			CfgNode cfgNode = new CfgNodeEcho(atts0.getPlace(), node);
			connect(atts0.getCfg(), cfgNode);
			myAtts.setCfg(new Cfg(
					atts0.getCfg().getHead(),
					cfgNode));
			break;
		}
		}

		return myAtts;
	}

	// for_expr *****************************************************************

	// - cfg
	// - place
	TacAttributes for_expr(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> empty			
		case PhpSymbols.T_EPSILON:
		{
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			myAtts.setPlace(Constant.TRUE);
			break;
		}

		// -> non_empty_for_expr	
		case PhpSymbols.non_empty_for_expr:
		{
			TacAttributes atts0 = this.non_empty_for_expr(firstChild);
			myAtts.setCfg(atts0.getCfg());
			myAtts.setPlace(atts0.getPlace());
			break;
		}
		}

		return myAtts;
	}


	// non_empty_for_expr *****************************************************************

	// - cfg
	// - place
	TacAttributes non_empty_for_expr(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> non_empty_for_expr , expr 
		case PhpSymbols.non_empty_for_expr:
		{
			TacAttributes atts0 = this.non_empty_for_expr(firstChild);
			TacAttributes atts2 = this.expr(node.getChild(2));

			connect(atts0.getCfg(), atts2.getCfg());
			myAtts.setCfg(new Cfg(
					atts0.getCfg().getHead(),
					atts2.getCfg().getTail(),
					atts2.getCfg().getTailEdgeType()));

			myAtts.setPlace(atts2.getPlace());

			break;
		}

		// -> expr					
		case PhpSymbols.expr:
		{
			TacAttributes atts0 = this.expr(firstChild);
			myAtts.setCfg(atts0.getCfg());
			myAtts.setPlace(atts0.getPlace());
			break;
		}
		}

		return myAtts;
	}



	// lexical_vars *****************************************************************

	// - cfg
	// - place
	TacAttributes lexical_vars(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {


		// -> /* empty */		
		case PhpSymbols.T_EPSILON:
		{
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			myAtts.setPlace(Constant.TRUE);
			break;
		}

		// ->  T_USE T_OPEN_BRACES lexical_var_list T_CLOSE_BRACES 					
		case PhpSymbols.T_USE:
		{

			//Mona Nashaat			
			TacAttributes atts0 = this.lexical_var_list(node.getChild(2));
			myAtts.setCfg(atts0.getCfg());
			myAtts.setPlace(atts0.getPlace());
			break;	


		}
		}

		return myAtts;
	}


	// lexical_var_list *****************************************************************

	// - cfg
	// - place
	TacAttributes lexical_var_list(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {


		// -> lexical_var_list		
		case PhpSymbols.lexical_var_list:
		{
			// -> lexical_var_list T_COMMA T_VARIABLE 
			if(node.getNumChildren()==3)
			{			

				TacPlace paramPlace = this.makePlace(node.getChild(2).getLexeme());
				TacFormalParam param = new TacFormalParam(paramPlace.getVariable(), true);
				TacAttributes attsList = this.lexical_var_list(firstChild);
				List<TacFormalParam> paramList = attsList.getFormalParamList();
				paramList.add(param);
				myAtts.setFormalParamList(paramList);
			}
			//-> lexical_var_list T_COMMA T_BITWISE_AND T_VARIABLE
			else
			{
				TacAttributes attsList = this.lexical_var_list(firstChild);
				TacPlace paramPlace = this.makePlace(node.getChild(3).getLexeme());
				TacFormalParam param = new TacFormalParam(paramPlace.getVariable(), true);
				List<TacFormalParam> paramList = attsList.getFormalParamList();
				paramList.add(param);
				myAtts.setFormalParamList(paramList);
			}


			break;
		}

		// ->  T_VARIABLE			
		case PhpSymbols.T_VARIABLE:
		{

			//Mona Nashaat :Needs Implementations

			TacPlace paramPlace = this.makePlace(firstChild.getLexeme());
			TacFormalParam param = new TacFormalParam(paramPlace.getVariable());
			List<TacFormalParam> paramList = new LinkedList<TacFormalParam>();
			paramList.add(param);
			myAtts.setFormalParamList(paramList);
			break;

		}
		// -> T_BITWISE_AND T_VARIABLE	
		case PhpSymbols.T_BITWISE_AND:
		{

			//Mona Nashaat

			TacPlace paramPlace = this.makePlace(node.getChild(1).getLexeme());
			TacFormalParam param = new TacFormalParam(paramPlace.getVariable(), true);
			List<TacFormalParam> paramList = new LinkedList<TacFormalParam>();
			paramList.add(param);
			myAtts.setFormalParamList(paramList);
			break;

		}
		}

		return myAtts;
	}

	// function_call *****************************************************************

	// - code
	// - place
	TacAttributes function_call(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();
		TacPlace tempVar= newTemp();
		// -> namespace_name (function_call_parameter_list )
		if (node.getChild(0).getSymbol() == PhpSymbols.namespace_name) {

			// dynamic dispatch problem! examples:
			// $a(...)
			// $a->foo(...)

			TacAttributes attsList = this.function_call_parameter_list(node.getChild(2));
			TacAttributes attsnamespace_name = this.namespace_name(node.getChild(0));
			String functionName=attsnamespace_name.getEncapsListString();


			Cfg callCfg = this.functionCallHelper(
					functionName, false, null, attsList.getActualParamList(),
					tempVar, true, node, null, null);

			connect(attsList.getCfg(), callCfg.getHead());

			myAtts.setCfg(new Cfg(
					attsList.getCfg().getHead(),
					callCfg.getTail()));

			myAtts.setPlace(tempVar);

			//=>    T_NAMESPACE T_NS_SEPARATOR namespace_name (function_call_parameter_list )
		} else  if (node.getChild(0).getSymbol() == PhpSymbols.T_NAMESPACE){

			TacAttributes atts1 = this.function_call_parameter_list(node.getChild(4));
			TacAttributes attsnamespace_name = this.namespace_name(node.getChild(2));
			String functionName=attsnamespace_name.getEncapsListString();

			Cfg callCfg = this.functionCallHelper(
					functionName, false, null, atts1.getActualParamList(),
					tempVar, true, node, null, null);

			connect(atts1.getCfg(), callCfg.getHead());

			myAtts.setCfg(new Cfg(
					atts1.getCfg().getHead(),
					callCfg.getTail()));

			myAtts.setPlace(tempVar);

		}

		//=>    T_NS_SEPARATOR namespace_name (function_call_parameter_list  )
		else  if (node.getChild(0).getSymbol() == PhpSymbols.T_NS_SEPARATOR){

			TacAttributes atts1 = this.function_call_parameter_list(node.getChild(4));

			TacAttributes attsnamespace_name = this.namespace_name(node.getChild(1));
			String functionName=attsnamespace_name.getEncapsListString();

			Cfg callCfg = this.functionCallHelper(
					functionName, false, null, atts1.getActualParamList(),
					tempVar, true, node, null, null);

			connect(atts1.getCfg(), callCfg.getHead());

			myAtts.setCfg(new Cfg(
					atts1.getCfg().getHead(),
					callCfg.getTail()));

			myAtts.setPlace(tempVar);			

		}

		else  if (node.getChild(0).getSymbol() == PhpSymbols.class_name){

			// -> class_name T_DOUBLE_COLON T_STRING T_OPEN_BRACES  
			//function_call_parameter_list
			//T_CLOSE_BRACES
			if(node.getChild(2).getSymbol()==PhpSymbols.T_STRING){


				TacAttributes attsList = this.function_call_parameter_list(node.getChild(4));


				TacAttributes atts0 = this.class_name(node.getChild(0));
				String className= atts0.getEncapsListString();

				// quick hack: get the method's name
				String methodName = null;
				try {
					ParseNode nameNode = node.getChild(2);
					if (nameNode.getSymbol() == PhpSymbols.T_STRING) {
						methodName = nameNode.getLexeme().toLowerCase() + InternalStrings.methodSuffix;
					}
				} catch (NullPointerException e) {
					// do nothing
				}

				Cfg callCfg;
				if (methodName == null) {
					// simply use a cfgnodecallunknown
					CfgNodeCallUnknown callUnknown = new CfgNodeCallUnknown(
							"<unknown>", attsList.getActualParamList(), tempVar, node, true);
					callCfg = new Cfg(callUnknown, callUnknown);

				} else {
					// try to backpatch
					callCfg = this.functionCallHelper(
							methodName, true, null, attsList.getActualParamList(),
							tempVar, true, node, className, null);
				}

				connect(attsList.getCfg(), callCfg.getHead());

				myAtts.setCfg(new Cfg(
						attsList.getCfg().getHead(),
						callCfg.getTail()));

				myAtts.setPlace(tempVar);
			}

			// ->  class_name T_DOUBLE_COLON variable_without_objects T_OPEN_BRACES 
			//	function_call_parameter_list
			//	T_CLOSE_BRACES
			else{


				TacAttributes attsList = this.function_call_parameter_list(node.getChild(4));

				String className="";
				if (node.getChild(0).tokenContent()!=""){
					className = node.getChild(0).tokenContent().toLowerCase();
				}

				// quick hack: get the method's name
				String methodName = null;
				try {
					ParseNode nameNode = node.getChild(2).getChild(0);
					if (nameNode.getSymbol() == PhpSymbols.variable_without_objects) {
						//TODO: What Variable_without_objects do???

						methodName = nameNode.getLexeme().toLowerCase() + InternalStrings.methodSuffix;
					}
				} catch (NullPointerException e) {
					// do nothing
				}

				Cfg callCfg;
				if (methodName == null) {
					// simply use a cfgnodecallunknown
					CfgNodeCallUnknown callUnknown = new CfgNodeCallUnknown(
							"<unknown>", attsList.getActualParamList(), tempVar, node, true);
					callCfg = new Cfg(callUnknown, callUnknown);

				} else {
					// try to backpatch
					callCfg = this.functionCallHelper(
							methodName, true, null, attsList.getActualParamList(),
							tempVar, true, node, className, null);
				}

				connect(attsList.getCfg(), callCfg.getHead());

				myAtts.setCfg(new Cfg(
						attsList.getCfg().getHead(),
						callCfg.getTail()));

				myAtts.setPlace(tempVar);

			}

		}      

		else  if (node.getChild(0).getSymbol() == PhpSymbols.reference_variable){

			// -> reference_variable T_DOUBLE_COLON T_STRING T_OPEN_BRACES 
			//	function_call_parameter_list
			//	T_CLOSE_BRACES
			if(node.getChild(2).getSymbol()==PhpSymbols.T_STRING){

				TacAttributes attsList = this.function_call_parameter_list(node.getChild(4));

				//TODO: what refrence_variable do?



				//String className = this.reference_variable(node.getChild(0));
				String className = node.getChild(0).getLexeme().toLowerCase();

				// quick hack: get the method's name
				String methodName = null;
				try {
					ParseNode nameNode = node.getChild(2).getChild(0);
					if (nameNode.getSymbol() == PhpSymbols.T_STRING) {
						methodName = nameNode.getLexeme().toLowerCase() + InternalStrings.methodSuffix;
					}
				} catch (NullPointerException e) {
					// do nothing
				}

				Cfg callCfg;
				if (methodName == null) {
					// simply use a cfgnodecallunknown
					CfgNodeCallUnknown callUnknown = new CfgNodeCallUnknown(
							"<unknown>", attsList.getActualParamList(), tempVar, node, true);
					callCfg = new Cfg(callUnknown, callUnknown);

				} else {
					// try to backpatch
					callCfg = this.functionCallHelper(
							methodName, true, null, attsList.getActualParamList(),
							tempVar, true, node, className, null);
				}

				connect(attsList.getCfg(), callCfg.getHead());

				myAtts.setCfg(new Cfg(
						attsList.getCfg().getHead(),
						callCfg.getTail()));

				myAtts.setPlace(tempVar);


			}

			// ->   reference_variable T_DOUBLE_COLON variable_without_objects T_OPEN_BRACES 
			//	function_call_parameter_list
			//	T_CLOSE_BRACES
			else{

				TacAttributes attsList = this.function_call_parameter_list(node.getChild(4));

				//TODO: what refrence_variable do?
				//String className = this.reference_variable(node.getChild(0));
				String className = node.getChild(0).getLexeme().toLowerCase();

				// quick hack: get the method's name
				String methodName = null;
				try {
					ParseNode nameNode = node.getChild(2).getChild(0);
					if (nameNode.getSymbol() == PhpSymbols.variable_without_objects) {
						//TODO: What Variable_without_objects do???
						methodName = nameNode.getLexeme().toLowerCase() + InternalStrings.methodSuffix;
					}
				} catch (NullPointerException e) {
					// do nothing
				}

				Cfg callCfg;
				if (methodName == null) {
					// simply use a cfgnodecallunknown
					CfgNodeCallUnknown callUnknown = new CfgNodeCallUnknown(
							"<unknown>", attsList.getActualParamList(), tempVar, node, true);
					callCfg = new Cfg(callUnknown, callUnknown);

				} else {
					// try to backpatch
					callCfg = this.functionCallHelper(
							methodName, true, null, attsList.getActualParamList(),
							tempVar, true, node, className, null);
				}

				connect(attsList.getCfg(), callCfg.getHead());

				myAtts.setCfg(new Cfg(
						attsList.getCfg().getHead(),
						callCfg.getTail()));

				myAtts.setPlace(tempVar);


			}

		}         
		//variable_without_objects  T_OPEN_BRACES 
		//	function_call_parameter_list T_CLOSE_BRACES

		else  if (node.getChild(0).getSymbol() == PhpSymbols.variable_without_objects){



			TacAttributes attsList = this.function_call_parameter_list(node.getChild(2));

			//TODO: what to do with refrence_variable?
			//String className = this.reference_variable(node.getChild(0));
			//Mona here
			TacAttributes attsnamespace_name = this.variable_without_objects(node.getChild(0));
			String functionName=((Variable) attsnamespace_name.getPlace()).getName();


			Cfg callCfg = this.functionCallHelper(
					functionName, false, null, attsList.getActualParamList(),
					tempVar, true, node, null, null);

			connect(attsList.getCfg(), callCfg.getHead());

			myAtts.setCfg(new Cfg(
					attsList.getCfg().getHead(),
					callCfg.getTail()));

			myAtts.setPlace(tempVar);



		}        
		else  if (node.getChild(0).getSymbol() == PhpSymbols.object_dim_list){


			//heloooooooo
			if (node.getParent()==null||node.getParent().getChild(3)==null||node.getParent().getChild(3).getChild(1)==null){
				CfgNode emptyNode = new CfgNodeEmpty();
				myAtts.setCfg(new Cfg(emptyNode, emptyNode));
				return myAtts;
			}
			TacAttributes attsList = this.function_call_parameter_list(node.getParent().getChild(3).getChild(1));
			String className= node.getParent().getChild(0).getChild(0).getChild(0).getChild(0).getChild(0).tokenContent();
			ParseNode classNodee= node.getParent().getChild(0).getChild(0).getChild(0).getChild(0).getChild(0);
			Variable var=	this.mainSymbolTable.getVariable(className);

			//String ddclassName = this.classStack.getLast().getName();
			//TODO: what to do with refrence_variable?
			//String className = this.reference_variable(node.getChild(0));
			//Mona here
			//TacAttributes attsnamespace_name = this.variable_without_objects(node.getChild(0));
			//			String functionName=((Variable) attsnamespace_name.getPlace()).getName();
			String functionName=node.getChild(0).getChild(0).getChild(0).tokenContent();
			String functionFullName=className+"->"+functionName;
			//String functionFullName=functionName.toLowerCase()+InternalStrings.methodSuffix;


			//System.err.print("hellooooooooo "+functionFullName);
			Cfg callCfg = this.functionCallHelper(
					functionFullName, true, null, attsList.getActualParamList(),
					tempVar, true, node, className, null);

			connect(attsList.getCfg(), callCfg.getHead());

			myAtts.setCfg(new Cfg(
					attsList.getCfg().getHead(),
					callCfg.getTail()));

			myAtts.setPlace(tempVar);



		}        




		return myAtts;
	}

	// class_name *****************************************************************

	// - code
	// - place
	TacAttributes class_name(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		// -> T_STATIC
		if (node.getChild(0).getSymbol() == PhpSymbols.T_STATIC) {

			//Mona Nashaat
			myAtts.getEncapsList().add(new Literal("static"));      
			myAtts = this.namespace_name(node.getChild(0));

			//=>    namespace_name
		} else  if (node.getChild(0).getSymbol() == PhpSymbols.namespace_name){
			myAtts = this.namespace_name(node.getChild(0));

		}

		//=>    T_NAMESPACE T_NS_SEPARATOR namespace_name
		else  if (node.getChild(0).getSymbol() == PhpSymbols.T_NAMESPACE){
			myAtts = this.namespace_name(node.getChild(2));


		}
		//-> T_NS_SEPARATOR namespace_name     
		else  if (node.getChild(0).getSymbol() == PhpSymbols.T_NS_SEPARATOR){
			myAtts = this.namespace_name(node.getChild(1));
		}
		return myAtts;
	}

	// fully_qualified_class_name *****************************************************************

	// - cfg
	// - place
	TacAttributes fully_qualified_class_name(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {


		// -> namespace_name		
		case PhpSymbols.namespace_name:
		{
			myAtts = this.namespace_name(node.getChild(0));

			break;
		}

		// ->   T_NAMESPACE T_NS_SEPARATOR namespace_name			
		case PhpSymbols.T_NAMESPACE:
		{

			myAtts = this.namespace_name(node.getChild(2));
			break;


		}
		// ->  T_NS_SEPARATOR namespace_name 	
		case PhpSymbols.T_NS_SEPARATOR:
		{

			myAtts = this.namespace_name(node.getChild(1));
			break;


		}
		}

		return myAtts;
	}



	// class_name_reference *****************************************************************

	// - cfg
	// - place
	TacAttributes class_name_reference(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {


		// -> class_name		
		case PhpSymbols.class_name:
		{
			myAtts = this.class_name(node.getChild(0));

			break;
		}

		// ->  dynamic_class_name_reference
		case PhpSymbols.dynamic_class_name_reference:
		{

			myAtts = this.dynamic_class_name_reference(node.getChild(0));
			break;


		}

		}

		return myAtts;
	}
	// dynamic_class_name_reference *****************************************************************

	// - cfg
	// - place
	TacAttributes dynamic_class_name_reference(ParseNode node){
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		case PhpSymbols.base_variable:
		{
			// -> base_variable -> object_property  dynamic_class_name_variable_properties
			if (node.getNumChildren()>1)
			{
				//Mona Nashaat 
				TacAttributes atts0 = this.base_variable(node.getChild(0));
				TacAttributes atts2 = this.object_property(node.getChild(2), atts0.getPlace().getVariable(), 
						null, null);

				connect(atts0.getCfg(), atts2.getCfg());
				myAtts.setCfg(new Cfg(
						atts0.getCfg().getHead(),
						atts2.getCfg().getTail()));
				myAtts.setPlace(atts2.getPlace());
				myAtts.setIsKnownCall(atts2.isKnownCall());
			}
			// -> base_variable
			else
			{
				//Mona Nashaat

				myAtts = this.base_variable(node.getChild(0));
				myAtts.setIsKnownCall(false);

			}
			break;
		}
		}

		return myAtts;
	}


	// dynamic_class_name_variable_properties *****************************************************************

	// - cfg
	// - place
	TacAttributes dynamic_class_name_variable_properties(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {


		// -> dynamic_class_name_variable_properties -> object_property
		case PhpSymbols.dynamic_class_name_variable_properties:
		{
			TacAttributes atts0 = this.dynamic_class_name_variable_properties(node.getChild(0));
			TacAttributes atts2 = this.object_property(node.getChild(2), atts0.getPlace().getVariable(), 
					null, null);

			connect(atts0.getCfg(), atts2.getCfg());
			myAtts.setCfg(new Cfg(
					atts0.getCfg().getHead(),
					atts2.getCfg().getTail()));
			myAtts.setPlace(atts2.getPlace());
			myAtts.setIsKnownCall(atts2.isKnownCall());

			break;
		}
		case PhpSymbols.T_EPSILON:{
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			myAtts.setPlace(Constant.TRUE);
			break;
		}
		}

		return myAtts;
	}


	// exit_expr *****************************************************************

	// - cfg
	// - no place: not needed
	TacAttributes exit_expr(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		if (node.getChild(0).getSymbol() == PhpSymbols.T_EPSILON) {
			// -> empty
			CfgNode cfgNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(cfgNode, cfgNode, CfgEdge.NO_EDGE));
		} else {
			if (node.getChild(1).getSymbol() == PhpSymbols.T_CLOSE_BRACES) {
				// -> ( )
				CfgNode cfgNode = new CfgNodeEmpty();
				myAtts.setCfg(new Cfg(cfgNode, cfgNode, CfgEdge.NO_EDGE));
			} else {
				// -> ( expr )
				TacAttributes attsExpr = this.expr(node.getChild(1));
				myAtts.setCfg(new Cfg(
						attsExpr.getCfg().getHead(),
						attsExpr.getCfg().getTail(),
						CfgEdge.NO_EDGE));
			}
		}

		return myAtts;
	}


	// backticks_expr *****************************************************************

	// - cfg
	// - no place: not needed
	TacAttributes backticks_expr(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		if (node.getChild(0).getSymbol() == PhpSymbols.T_EPSILON) {
			// -> empty
			CfgNode cfgNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(cfgNode, cfgNode, CfgEdge.NO_EDGE));


		} else  if (node.getChild(0).getSymbol() == PhpSymbols.T_STRING) {
			// -> T_STRING
			myAtts.setPlace(new Literal(node.getChild(0).getLexeme()));

		}
		else if (node.getChild(0).getSymbol() == PhpSymbols.T_ENCAPSED_AND_WHITESPACE) {
			//-> T_ENCAPSED_AND_WHITESPACE
			myAtts.setPlace(new Literal(node.getChild(0).getLexeme()));
		}
		else if (node.getChild(0).getSymbol() == PhpSymbols.encaps_list) {
			//-> encaps_list

			TacPlace tempPlace = this.newTemp();
			TacAttributes attsList = this.encaps_list(node.getChild(0));

			EncapsList encapsList = attsList.getEncapsList(); 
			TacAttributes deepList = encapsList.makeAtts(newTemp(), node);

			List<TacActualParam> paramList = new LinkedList<TacActualParam>();
			paramList.add(new TacActualParam(deepList.getPlace(), false));

			Cfg execCallCfg = this.functionCallHelper(
					"shell_exec", false, null, paramList,
					tempPlace, true, node, null, null);

			connect(deepList.getCfg(), execCallCfg.getHead());
			myAtts.setCfg(new Cfg(
					deepList.getCfg().getHead(),
					execCallCfg.getTail()));

			myAtts.setPlace(tempPlace);
		}

		return myAtts;
	}

	// ctor_arguments *****************************************************************

	// - cfg
	TacAttributes ctor_arguments(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		if (node.getChild(0).getSymbol() == PhpSymbols.T_EPSILON) {
			// -> empty
			CfgNode cfgNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(cfgNode, cfgNode));
			List<TacActualParam> ll = new LinkedList<TacActualParam>();
			myAtts.setActualParamList(ll);
		} else {
			// -> ( function_call_parameter_list )
			TacAttributes attsList = this.function_call_parameter_list(node.getChild(1));
			myAtts.setCfg(attsList.getCfg());
			myAtts.setActualParamList(attsList.getActualParamList());
		}

		return myAtts;
	}


	// common_scalar *******************************************************************

	// - cfg (always just an empty node)
	// - place
	TacAttributes common_scalar(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// T_LNUMBER
		case PhpSymbols.T_LNUMBER: 
		{
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			myAtts.setPlace(new Literal(firstChild.getLexeme()));
			break;
		}

		// T_DNUMBER
		case PhpSymbols.T_DNUMBER: 
		{
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			myAtts.setPlace(new Literal(firstChild.getLexeme()));
			break;
		}

		// T_CONSTANT_ENCAPSED_STRING
		// simple string inside single or double quotes, no variables within
		case PhpSymbols.T_CONSTANT_ENCAPSED_STRING: 
		{
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			//   myAtts.setPlace(new Literal(firstChild.getLexeme()));
			myAtts.setPlace(new Literal(firstChild.tokenContent()));
			break;
		}

		// T_LINE
		// magic constant __LINE__
		case PhpSymbols.T_LINE: 
		{
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			myAtts.setPlace(this.lineCPlace);
			break;
		}

		// T_FILE
		// magic constant __FILE__
		case PhpSymbols.T_FILE: 
		{
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			myAtts.setPlace(new Literal(this.file.getPath()));
			//myAtts.setPlace(this.fileCPlace);
			break;
		}

		// T_CLASS_C
		// magic constant __CLASS__
		case PhpSymbols.T_CLASS_C: 
		{
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			myAtts.setPlace(this.classCPlace);
			break;
		}

		// T_FUNC_C
		// magic constant __FUNC__
		case PhpSymbols.T_FUNC_C: 
		{
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			// if you want to resolve this in more detail, you
			// have to consider that
			// - for the main function, the empty string is returned
			// - the value of this constant is affected by inclusions;
			//   this means that you must not resolve this before all
			//   inclusion iterations are over
			myAtts.setPlace(this.functionCPlace);
			break;
		}

		// ->T_DIR
		case PhpSymbols.T_DIR: 
		{
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			// if you want to resolve this in more detail, you
			// have to consider that
			// - for the main function, the empty string is returned
			// - the value of this constant is affected by inclusions;
			//   this means that you must not resolve this before all
			//   inclusion iterations are over
			//Dir_Place
			//  myAtts.setPlace(this.dirPlace);
			break;
		}

		// ->T_METHOD_C
		case PhpSymbols.T_METHOD_C: 
		{
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			// if you want to resolve this in more detail, you
			// have to consider that
			// - for the main function, the empty string is returned
			// - the value of this constant is affected by inclusions;
			//   this means that you must not resolve this before all
			//   inclusion iterations are over
			//Dir_Place
			myAtts.setPlace(this.methodCPlace);
			break;
		}
		// ->T_NS_C
		case PhpSymbols.T_NS_C: 
		{
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			// if you want to resolve this in more detail, you
			// have to consider that
			// - for the main function, the empty string is returned
			// - the value of this constant is affected by inclusions;
			//   this means that you must not resolve this before all
			//   inclusion iterations are over
			//T_NS_C
			myAtts.setPlace(this.namespaceCPlace);
			break;
		}


		case PhpSymbols.T_START_HEREDOC:
		{     // -> T_START_HEREDOC T_ENCAPSED_AND_WHITESPACE T_END_HEREDOC


			if(node.getChild(1).getSymbol()==PhpSymbols.T_ENCAPSED_AND_WHITESPACE){

				TacAttributes atts = new TacAttributes();

				atts.setPlace(new Literal(node.getChild(1).getLexeme()));		

				CfgNode emptyNode = new CfgNodeEmpty();
				atts.setCfg(new Cfg(emptyNode, emptyNode));


				myAtts.setCfg(atts.getCfg());
				myAtts.setPlace(atts.getPlace());
			}

			//->   T_START_HEREDOC T_CONSTANT_ENCAPSED_STRING T_END_HEREDOC
			else if(node.getChild(1).getSymbol()==PhpSymbols.T_CONSTANT_ENCAPSED_STRING){

				TacAttributes atts = new TacAttributes();

				atts.setPlace(new Literal(node.getChild(1).getLexeme()));

				CfgNode emptyNode = new CfgNodeEmpty();
				atts.setCfg(new Cfg(emptyNode, emptyNode));

				myAtts.setCfg(atts.getCfg());
				myAtts.setPlace(atts.getPlace());
			}
			//->T_START_HEREDOC T_END_HEREDOC
			else 
			{
				//Mona Nashaat
				CfgNode emptyNode = new CfgNodeEmpty();
				myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			}

			break;
		}
		}

		return myAtts;
	}


	// static_expr *****************************************************************

	// - cfg
	// - place
	TacAttributes static_expr(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> common_scalar		
		case PhpSymbols.common_scalar:
		{
			myAtts = this.common_scalar(firstChild);
			break;
		}
		// -> namespace_name		
		case PhpSymbols.namespace_name:
		{
			TacAttributes atts = this.namespace_name(firstChild);
			CfgNode cfgNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(cfgNode, cfgNode));
			myAtts.setPlace(makeConstantPlace(atts.getEncapsListString()));
			break;
		}
		// -> T_NAMESPACE T_NS_SEPARATOR namespace_name	
		case PhpSymbols.T_NAMESPACE:
		{

			TacAttributes atts = this.namespace_name(node.getChild(2));
			CfgNode cfgNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(cfgNode, cfgNode));
			myAtts.setPlace(makeConstantPlace(atts.getEncapsListString()));
			break;

		}


		// -> T_NS_SEPARATOR namespace_name 
		case PhpSymbols.T_NS_SEPARATOR:
		{

			TacAttributes atts = this.namespace_name(node.getChild(1));
			CfgNode cfgNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(cfgNode, cfgNode));
			myAtts.setPlace(makeConstantPlace(atts.getEncapsListString()));
			break;
		}

		// -> + static_expr	
		case PhpSymbols.T_PLUS:
		{
			Variable tempPlace = this.newTemp();
			TacAttributes atts1 = this.static_expr(node.getChild(1));
			CfgNode cfgNode = new CfgNodeAssignUnary(
					tempPlace, atts1.getPlace(), TacOperators.PLUS, node);
			connect(atts1.getCfg(), cfgNode);
			myAtts.setCfg(new Cfg(atts1.getCfg().getHead(), cfgNode));
			myAtts.setPlace(tempPlace);
			break;
		}

		// -> - static_expr	
		case PhpSymbols.T_MINUS:
		{
			Variable tempPlace = this.newTemp();
			TacAttributes atts1 = this.static_expr(node.getChild(1));
			CfgNode cfgNode = new CfgNodeAssignUnary(
					tempPlace, atts1.getPlace(), TacOperators.MINUS, node);
			connect(atts1.getCfg(), cfgNode);
			myAtts.setCfg(new Cfg(atts1.getCfg().getHead(), cfgNode));
			myAtts.setPlace(tempPlace);
			break;
		}

		// -> T_ARRAY ( static_array_pair_list ) 
		case PhpSymbols.T_ARRAY:
		{
			Variable arrayPlace = this.newTemp();
			TacAttributes attsList = this.static_array_pair_list(
					node.getChild(2), 
					arrayPlace);

			CfgNode cfgNode = new CfgNodeAssignArray(arrayPlace, node);
			connect(cfgNode, attsList.getCfg());

			myAtts.setCfg(new Cfg(
					cfgNode,
					attsList.getCfg().getTail()));
			myAtts.setPlace(arrayPlace);

			break;
		}

		// -> static_class_constant
		case PhpSymbols.static_class_constant:
		{
			Variable tempPlace = this.newTemp();
			TacAttributes atts1 = this.static_class_constant(node.getChild(0));
			CfgNode cfgNode =new CfgNodeStatic(atts1.getPlace(), node.getChild(0));


			connect(atts1.getCfg(), cfgNode);
			myAtts.setCfg(new Cfg(atts1.getCfg().getHead(), cfgNode));
			myAtts.setPlace(tempPlace);
			break;		


		}
		}

		return myAtts;
	}

	// static_class_constant *****************************************************************

	// - cfg
	// - place
	TacAttributes static_class_constant(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> class_name T_DOUBLE_COLON T_STRING		
		case PhpSymbols.class_name:
		{

			Variable var = this.newTemp();
			var.SetStatic(true);
			TacAttributes atts0 = this.class_name(firstChild);
			String ClassName= atts0.getEncapsListString();
			var.SetisCustomObject(true);
			var.SetCustomClass(ClassName);

			CfgNode cfgNode = new CfgNodeAssignSimple(var, this.constantsTable.getConstant("NULL"), node.getChild(2));
			Cfg nullCfg = new Cfg(cfgNode, cfgNode);

			myAtts.setCfg(nullCfg);

			break;
		}

		}

		return myAtts;
	}
	// scalar **************************************************************************

	// - cfg
	// - place
	TacAttributes scalar(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {


		//->class_constant
		case PhpSymbols.class_constant:
		{
			myAtts = this.class_constant(node.getChild(0));

			break;
		}

		// -> namespace_name		
		case PhpSymbols.namespace_name:
		{
			myAtts = this.namespace_name(firstChild);
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));

			break;
		}
		// -> T_NAMESPACE T_NS_SEPARATOR namespace_name	
		case PhpSymbols.T_NAMESPACE:
		{
			myAtts = this.namespace_name(firstChild);
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			break;
		}


		// -> T_NS_SEPARATOR namespace_name 
		case PhpSymbols.T_NS_SEPARATOR:
		{
			myAtts = this.namespace_name(firstChild);
			break;
		}


		// -> common_scalar
		case PhpSymbols.common_scalar:
		{
			TacAttributes atts0 = this.common_scalar(firstChild);
			myAtts.setCfg(atts0.getCfg());
			myAtts.setPlace(atts0.getPlace());
			break;
		}


		case PhpSymbols.T_DOUBLE_QUOTE:
		{
			// -> " encaps_list "
			// double quotes enclosing something that also contains at least
			// one variable
			if(node.getChild(1).getSymbol()==PhpSymbols.encaps_list){
				TacAttributes attsList = this.encaps_list(node.getChild(1));
				//myAtts.setCfg(oldAttsList.getCfg());
				//myAtts.setPlace(oldAttsList.getPlace());

				EncapsList encapsList = attsList.getEncapsList();
				TacAttributes deepList = encapsList.makeAtts(newTemp(), node);
				myAtts.setCfg(deepList.getCfg());
				myAtts.setPlace(deepList.getPlace());
			}
			// -> T_DOUBLE_QUOTE T_ENCAPSED_AND_WHITESPACE T_DOUBLE_QUOTE

			else
			{
				//Mona Nashaat

				TacAttributes atts = new TacAttributes();

				atts.setPlace(new Literal(node.getChild(1).getLexeme()));

				CfgNode emptyNode = new CfgNodeEmpty();
				atts.setCfg(new Cfg(emptyNode, emptyNode));

				myAtts.setCfg(atts.getCfg());
				myAtts.setPlace(atts.getPlace());
			}
			break;
		}

		// -> ' encaps_list '
		//Mona Nashaat: comment this case
		//         case PhpSymbols.T_SINGLE_QUOTE:
		//         {
		// seems to be an unreachable production
		//             System.out.println(node.getName());
		//             throw new RuntimeException("scalar: unreachable position?");
		//            //break;
		//         }

		// -> T_START_HEREDOC encaps_list T_END_HEREDOC
		// works the same way as " encaps_list "
		case PhpSymbols.T_START_HEREDOC:
		{
			TacAttributes attsList = this.encaps_list(node.getChild(1));
			//myAtts.setCfg(attsList.getCfg());
			//myAtts.setPlace(attsList.getPlace());

			EncapsList encapsList = attsList.getEncapsList(); 
			TacAttributes deepList = encapsList.makeAtts(newTemp(), node);
			myAtts.setCfg(deepList.getCfg());
			myAtts.setPlace(deepList.getPlace());

			break;
		}

		}

		return myAtts;
	}
	// static_array_pair_list *****************************************************************

	// - cfg
	TacAttributes static_array_pair_list(ParseNode node, TacPlace arrayPlace) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);

		if (firstChild.getSymbol() == PhpSymbols.T_EPSILON) {
			// -> empty
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
		} else {
			// -> non_empty_static_array_pair_list possible_comma	
			TacAttributes atts0 = 
					this.non_empty_static_array_pair_list(firstChild, arrayPlace);
			myAtts.setCfg(atts0.getCfg());
		}

		return myAtts;
	}


	// non_empty_static_array_pair_list *****************************************************************

	// - cfg
	// very similar to parts of non_empty_array_pair_list

	TacAttributes non_empty_static_array_pair_list(ParseNode node, TacPlace arrayPlace) {
		TacAttributes myAtts = new TacAttributes();

		int logId = this.tempId;
		ParseNode firstChild = node.getChild(0);
		if (firstChild.getSymbol() == PhpSymbols.non_empty_static_array_pair_list) {
			if (node.getNumChildren() == 5) {

				// -> non_empty_static_array_pair_list , static_expr T_DOUBLE_ARROW static_expr

				TacAttributes attsList = null;
				try {
					attsList = this.non_empty_static_array_pair_list(firstChild, arrayPlace);
				} catch (StackOverflowError e) {
					System.out.println(node.getName());
					Utils.bail();
				}
				TacAttributes attsScalar1 = this.static_expr(node.getChild(2));
				TacAttributes attsScalar2 = this.static_expr(node.getChild(4));

				CfgNode cfgNode = this.arrayPairListHelper(
						arrayPlace, attsScalar1.getPlace(), 
						attsScalar2.getPlace(), false, node);

				connect(attsList.getCfg(), attsScalar1.getCfg());
				connect(attsScalar1.getCfg(), attsScalar2.getCfg());
				connect(attsScalar2.getCfg(), cfgNode);

				myAtts.setCfg(new Cfg(
						attsList.getCfg().getHead(),
						cfgNode));

			} else {

				// -> non_empty_static_array_pair_list , static_expr

				TacAttributes attsList = 
						this.non_empty_static_array_pair_list(firstChild, arrayPlace);
				TacAttributes attsScalar = this.static_expr(node.getChild(2));

				TacPlace offsetPlace;
				int largestIndex = attsList.getArrayIndex();
				if (largestIndex == -1) {
					offsetPlace = this.emptyOffsetPlace;
				} else {
					largestIndex++;
					offsetPlace = new Literal(String.valueOf(largestIndex));
					myAtts.setArrayIndex(largestIndex);
				}

				CfgNode cfgNode = this.arrayPairListHelper(
						arrayPlace, 
						offsetPlace,
						attsScalar.getPlace(), false, node);

				connect(attsList.getCfg(), attsScalar.getCfg());
				connect(attsScalar.getCfg(), cfgNode);

				myAtts.setCfg(new Cfg(
						attsList.getCfg().getHead(),
						cfgNode));
			}
		} else {
			if (node.getNumChildren() == 3) {

				// -> static_expr T_DOUBLE_ARROW static_expr

				TacAttributes attsScalar1 = this.static_expr(firstChild);
				TacAttributes attsScalar2 = this.static_expr(node.getChild(2));

				CfgNode cfgNode = this.arrayPairListHelper(
						arrayPlace, attsScalar1.getPlace(), attsScalar2.getPlace(), false, node);
				connect(attsScalar1.getCfg(), attsScalar2.getCfg());
				connect(attsScalar2.getCfg(), cfgNode);

				myAtts.setCfg(new Cfg(
						attsScalar1.getCfg().getHead(),
						cfgNode));

			} else {
				// -> static_expr 

				TacAttributes attsScalar = this.static_expr(firstChild);

				CfgNode cfgNode = this.arrayPairListHelper(
						arrayPlace, new Literal("0"), attsScalar.getPlace(), 
						false, node);

				connect(attsScalar.getCfg(), cfgNode);

				myAtts.setCfg(new Cfg(
						attsScalar.getCfg().getHead(),
						cfgNode));

				myAtts.setArrayIndex(0);
			}
		}

		this.resetId(logId);
		return myAtts;
	}


	// internal_functions_in_yacc *****************************************************************

	// - cfg
	// - place
	TacAttributes internal_functions_in_yacc(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> T_ISSET ( isset_variables )
		case PhpSymbols.T_ISSET:
		{
			TacAttributes atts2 = this.isset_variables(node.getChild(2));
			myAtts.setCfg(atts2.getCfg());
			myAtts.setPlace(atts2.getPlace());
			break;
		}

		// -> T_EMPTY ( variable )
		case PhpSymbols.T_EMPTY:
		{
			TacPlace tempPlace = this.newTemp();
			TacAttributes attsCvar = this.variable(node.getChild(2));
			CfgNode cfgNode = 
					new CfgNodeEmptyTest(tempPlace, attsCvar.getPlace(), node);
			connect(attsCvar.getCfg(), cfgNode);
			myAtts.setCfg(new Cfg(
					attsCvar.getCfg().getHead(),
					cfgNode));
			myAtts.setPlace(tempPlace);
			break;
		}

		// -> T_INCLUDE expr
		case PhpSymbols.T_INCLUDE:
		{
			TacPlace tempPlace = this.newTemp();
			TacAttributes attsExpr = this.expr(node.getChild(1));
			CfgNodeInclude cfgNode = new CfgNodeInclude(tempPlace, attsExpr.getPlace(), 
					this.file, (TacFunction) this.functionStack.getLast(), node);
			this.includeNodes.add(cfgNode);
			connect(attsExpr.getCfg(), cfgNode);
			myAtts.setCfg(new Cfg(
					attsExpr.getCfg().getHead(),
					cfgNode));
			myAtts.setPlace(tempPlace);
			break;
		}

		// -> T_INCLUDE_ONCE expr
		case PhpSymbols.T_INCLUDE_ONCE:
		{
			TacPlace tempPlace = this.newTemp();
			TacAttributes attsExpr = this.expr(node.getChild(1));
			CfgNodeInclude cfgNode = new CfgNodeInclude(tempPlace, attsExpr.getPlace(), 
					this.file, (TacFunction) this.functionStack.getLast(), node);
			this.includeNodes.add(cfgNode);
			connect(attsExpr.getCfg(), cfgNode);
			myAtts.setCfg(new Cfg(
					attsExpr.getCfg().getHead(),
					cfgNode));
			myAtts.setPlace(tempPlace);
			break;
		}

		// -> T_EVAL ( expr )
		case PhpSymbols.T_EVAL:
		{
			TacPlace tempPlace = this.newTemp();
			TacAttributes attsExpr = this.expr(node.getChild(2));
			CfgNode cfgNode = new CfgNodeEval(tempPlace, attsExpr.getPlace(), node);
			connect(attsExpr.getCfg(), cfgNode);
			myAtts.setCfg(new Cfg(
					attsExpr.getCfg().getHead(),
					cfgNode));
			myAtts.setPlace(tempPlace);
			break;
		}

		// -> T_REQUIRE expr
		case PhpSymbols.T_REQUIRE:
		{
			// no need to distinguish between "require" and "include"
			TacPlace tempPlace = this.newTemp();
			TacAttributes attsExpr = this.expr(node.getChild(1));
			CfgNodeInclude cfgNode = new CfgNodeInclude(tempPlace, attsExpr.getPlace(), 
					this.file, (TacFunction) this.functionStack.getLast(), node);
			this.includeNodes.add(cfgNode);
			connect(attsExpr.getCfg(), cfgNode);
			myAtts.setCfg(new Cfg(
					attsExpr.getCfg().getHead(),
					cfgNode));
			myAtts.setPlace(tempPlace);
			break;
		}

		// -> T_REQUIRE_ONCE expr
		case PhpSymbols.T_REQUIRE_ONCE:
		{
			TacPlace tempPlace = this.newTemp();
			TacAttributes attsExpr = this.expr(node.getChild(1));
			CfgNodeInclude cfgNode = new CfgNodeInclude(tempPlace, attsExpr.getPlace(), 
					this.file, (TacFunction) this.functionStack.getLast(), node);
			this.includeNodes.add(cfgNode);
			connect(attsExpr.getCfg(), cfgNode);
			myAtts.setCfg(new Cfg(
					attsExpr.getCfg().getHead(),
					cfgNode));
			myAtts.setPlace(tempPlace);
			break;
		}
		}

		return myAtts;
	}

	// isset_variables *****************************************************************

	// - cfg
	// - place
	TacAttributes isset_variables(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		Variable tempPlace = this.newTemp();

		ParseNode firstChild = node.getChild(0);
		//Mona Nashaat: change cvar to variable
		if (firstChild.getSymbol() == PhpSymbols.variable) {
			// -> variable
			TacAttributes attsCvar = this.variable(firstChild);

			CfgNode cfgNode = new CfgNodeIsset(tempPlace, attsCvar.getPlace(), node);

			connect(attsCvar.getCfg(), cfgNode);
			myAtts.setCfg(new Cfg(
					attsCvar.getCfg().getHead(),
					cfgNode));
			myAtts.setPlace(tempPlace);

		} else {
			// -> isset_variables , variable
			TacAttributes attsVariables = this.isset_variables(firstChild);
			TacAttributes attsCvar = this.variable(node.getChild(2));

			TacPlace tempPlaceIsset = this.newTemp();
			CfgNode cfgNodeIsset = new CfgNodeIsset(tempPlaceIsset, attsCvar.getPlace(), node);
			// no need for short-circuit code here
			CfgNode cfgNode = new CfgNodeAssignBinary(
					tempPlace, attsVariables.getPlace(), tempPlaceIsset, 
					TacOperators.BOOLEAN_AND, node);

			connect(attsVariables.getCfg(), attsCvar.getCfg());
			connect(attsCvar.getCfg(), cfgNodeIsset);
			connect(cfgNodeIsset, cfgNode);
			myAtts.setPlace(tempPlace);
			myAtts.setCfg(new Cfg(
					attsVariables.getCfg().getHead(),
					cfgNode));
		}

		return myAtts;
	}




	// class_constant *****************************************************************

	// - cfg
	// - place
	TacAttributes class_constant(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();
		ParseNode firstChild = node.getChild(0);

		if (firstChild.getSymbol() == PhpSymbols.class_name) {
			// -> class_name T_DOUBLE_COLON T_STRING

			Variable var = this.newTemp();

			TacAttributes atts0 = this.class_name(firstChild);
			String ClassName= atts0.getEncapsListString();
			var.SetisCustomObject(true);
			var.SetCustomClass(ClassName);

			CfgNode cfgNode = new CfgNodeAssignSimple(var, this.constantsTable.getConstant("NULL"), node.getChild(2));
			Cfg nullCfg = new Cfg(cfgNode, cfgNode);
			myAtts.setPlace(var);

			myAtts.setCfg(nullCfg);



		} else {
			// -> reference_variable T_DOUBLE_COLON T_STRING

			Variable var = this.newTemp();
			var.SetStatic(true);
			TacAttributes atts0 = this.reference_variable(firstChild);		
			//TODO: What to do with reference_variable?

			CfgNode cfgNode = new CfgNodeAssignSimple(var, this.constantsTable.getConstant("NULL"), node.getChild(2));
			Cfg nullCfg = new Cfg(cfgNode, cfgNode);

			myAtts.setPlace(var);
			myAtts.setCfg(nullCfg);


		}

		return myAtts;
	}



	// encaps_var_offset *****************************************************************

	// - place
	TacAttributes encaps_var_offset(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> T_STRING
		// constants are not expanded here
		case PhpSymbols.T_STRING:
		{
			myAtts.setPlace(new Literal(firstChild.getLexeme()));
			break;
		}

		// -> T_NUM_STRING
		case PhpSymbols.T_NUM_STRING:
		{
			myAtts.setPlace(new Literal(firstChild.getLexeme()));
			break;
		}

		// -> T_VARIABLE
		case PhpSymbols.T_VARIABLE:
		{
			myAtts.setPlace(this.makePlace(firstChild.getLexeme()));
			break;
		}
		}

		return myAtts;
	}


	// encaps_var *****************************************************************

	// - cfg
	// - place
	TacAttributes encaps_var(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		if (node.getNumChildren() == 1) {
			// -> T_VARIABLE 
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			myAtts.setPlace(makePlace(node.getChild(0).getLexeme()));
			return myAtts;
		}


		switch(node.getChild(1).getSymbol()) {

		// -> T_VARIABLE [ encaps_var_offset ]
		case PhpSymbols.T_OPEN_RECT_BRACES:
		{
			TacAttributes attsOffset = this.encaps_var_offset(node.getChild(2));
			TacPlace varPlace = this.makePlace(node.getChild(0).getLexeme());
			myAtts.setPlace(this.makeArrayElementPlace(
					varPlace, attsOffset.getPlace()));
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			break;
		}

		// -> T_VARIABLE T_OBJECT_OPERATOR T_STRING
		// access to a member variable
		case PhpSymbols.T_OBJECT_OPERATOR:
		{
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			myAtts.setPlace(this.memberPlace);
			break;
		}

		// -> T_DOLLAR_OPEN_CURLY_BRACES expr }
		case PhpSymbols.expr:
		{
			TacAttributes attsExpr = this.expr(node.getChild(1));
			TacPlace varPlace = this.exprVarHelper(attsExpr.getPlace());
			myAtts.setPlace(varPlace);
			myAtts.setCfg(attsExpr.getCfg());
			break;
		}

		// -> T_DOLLAR_OPEN_CURLY_BRACES T_STRING_VARNAME [ expr ] }
		case PhpSymbols.T_STRING_VARNAME:
		{
			if (node.getChild(2).getSymbol()==PhpSymbols.T_OPEN_RECT_BRACES){
				TacAttributes attsExpr = this.expr(node.getChild(3));
				TacPlace arrayPlace = this.makePlace("$" + node.getChild(1).getLexeme());
				TacPlace myPlace = this.makeArrayElementPlace(
						arrayPlace,
						attsExpr.getPlace());
				myAtts.setCfg(attsExpr.getCfg());
				myAtts.setPlace(myPlace);
			}
			//->  T_DOLLAR_OPEN_CURLY_BRACES T_STRING_VARNAME T_CLOSE_CURLY_BRACES 
			else  if (node.getChild(2).getSymbol()==PhpSymbols.T_CLOSE_CURLY_BRACES){
				// ex. a in "${a}"
				CfgNode cfgNode = new CfgNodeEmpty();
				myAtts.setCfg(new Cfg(cfgNode, cfgNode));
				myAtts.setPlace(new Literal(node.getChild(0).getLexeme()));
			}
			break;
		}



		//   -> T_CURLY_OPEN variable }
		//  Mona Nashaat: comment this case
		case PhpSymbols.variable:
		{
			// ex. "foo{$bar}blob"
			TacAttributes attsCvar = this.variable(node.getChild(1));
			myAtts.setPlace(attsCvar.getPlace());
			myAtts.setCfg(attsCvar.getCfg());
			break;
		}
		}

		return myAtts;
	}


	// expr ****************************************************************************

	// - cfg
	// - place
	TacAttributes expr(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> variable ...
		case PhpSymbols.variable:
		{
			// -> variable 
			if (node.getNumChildren()==1)
			{
				myAtts = this.variable(firstChild);
			}

			else {
				if (node.getChild(1).getSymbol()==PhpSymbols.T_ASSIGN){

					// -> variable = expr                	 
					if (node.getChild(2).getSymbol()==PhpSymbols.expr){
						//Mona Nashaat
						TacAttributes atts0 = this.variable(firstChild);
						TacAttributes atts2 = this.expr(node.getChild(2));

						CfgNode cfgNode = 
								new CfgNodeAssignSimple(
										(Variable) atts0.getPlace(), 
										atts2.getPlace(), node);
						connect(atts0.getCfg(), atts2.getCfg());
						connect(atts2.getCfg(), cfgNode);

						myAtts.setCfg(new Cfg(
								atts0.getCfg().getHead(),
								cfgNode));

						myAtts.setPlace(atts0.getPlace());

					}
					else {
						// -> variable = & variable     
						if (node.getChild(3).getSymbol()==PhpSymbols.variable){
							//Mona Nashaat
							TacAttributes atts0 = this.variable(firstChild);
							TacAttributes atts3 = this.variable(node.getChild(3));

							CfgNode cfgNode = new CfgNodeAssignRef(
									(Variable) atts0.getPlace(), 
									(Variable) atts3.getPlace(), node);
							connect(atts0.getCfg(), atts3.getCfg());
							connect(atts3.getCfg(), cfgNode);

							myAtts.setCfg(new Cfg(
									atts0.getCfg().getHead(),
									cfgNode,
									CfgEdge.NORMAL_EDGE));
							myAtts.setPlace(atts0.getPlace());
							break;
						}
						//->variable = & T_NEW class_name_reference  ctor_arguments
						else if (node.getChild(3).getSymbol()==PhpSymbols.T_NEW){


							//Mona Nashaat 

							// mostly analogous to the version without reference operator
							// (search for "t_new" below)

							TacAttributes attsCvar = this.variable(firstChild);
							TacAttributes attsCtor = this.ctor_arguments(node.getChild(5));

							// node for assigning some value to cvar
							// (depending on whether we can resolve the classname or not)
							CfgNode cfgNode;

							ParseNode classNameNode = node.getChild(4).getChild(0);
							if (classNameNode.getSymbol() == PhpSymbols.T_STRING) {

								// classname can be resolved

								String className = classNameNode.getLexeme().toLowerCase();

								Variable tempPlace = newTemp();

								Cfg callCfg = this.functionCallHelper(
										className + InternalStrings.methodSuffix, true, null, 
										attsCtor.getActualParamList(), tempPlace, true, node, 
										className, null);

								cfgNode = new CfgNodeAssignRef(
										(Variable) attsCvar.getPlace(), 
										tempPlace, node);

								connect(attsCvar.getCfg(), attsCtor.getCfg());
								connect(attsCtor.getCfg(), callCfg.getHead());
								connect(callCfg.getTail(), cfgNode);

								myAtts.setCfg(new Cfg(
										attsCtor.getCfg().getHead(),
										callCfg.getTail()));
								//myAtts.setClassName(className);

							} else {
								cfgNode = new CfgNodeAssignRef(
										(Variable) attsCvar.getPlace(), 
										this.objectPlace, node);
								connect(attsCvar.getCfg(), attsCtor.getCfg());
								connect(attsCtor.getCfg(), cfgNode);
							}

							myAtts.setCfg(new Cfg(
									attsCvar.getCfg().getHead(),
									cfgNode));
							myAtts.setPlace(attsCvar.getPlace());

							break;

						}
					}
				}
				//-> variable T_PLUS_EQUAL expr
				else if (node.getChild(1).getSymbol()==PhpSymbols.T_PLUS_EQUAL){
					//Mona Nashaat
					this.cvarOpExp(node, TacOperators.PLUS, myAtts); 
				}

				//-> variable T_MINUS_EQUAL expr
				else if (node.getChild(1).getSymbol()==PhpSymbols.T_MINUS_EQUAL){
					//Mona Nashaat
					this.cvarOpExp(node, TacOperators.MINUS, myAtts);

				}
				//-> variable T_MUL_EQUAL expr
				else if (node.getChild(1).getSymbol()==PhpSymbols.T_MUL_EQUAL){
					//Mona Nashaat
					this.cvarOpExp(node, TacOperators.MULT, myAtts);
				}


				//-> variable T_DIV_EQUAL expr
				else if (node.getChild(1).getSymbol()==PhpSymbols.T_DIV_EQUAL){
					//Mona Nashaat
					this.cvarOpExp(node, TacOperators.DIV, myAtts);

				}
				//-> variable T_CONCAT_EQUAL expr
				else if (node.getChild(1).getSymbol()==PhpSymbols.T_CONCAT_EQUAL){
					//Mona Nashaat
					this.cvarOpExp(node, TacOperators.CONCAT, myAtts);

				}

				//-> variable T_MOD_EQUAL expr
				else if (node.getChild(1).getSymbol()==PhpSymbols.T_MOD_EQUAL){
					//Mona Nashaat
					this.cvarOpExp(node, TacOperators.MODULO, myAtts);
				}

				//-> variable T_AND_EQUAL expr
				else if (node.getChild(1).getSymbol()==PhpSymbols.T_AND_EQUAL){
					//Mona Nashaat
					this.cvarOpExp(node, TacOperators.BITWISE_AND, myAtts);
				}

				//-> variable T_OR_EQUAL expr
				else if (node.getChild(1).getSymbol()==PhpSymbols.T_OR_EQUAL){
					//Mona Nashaat
					this.cvarOpExp(node, TacOperators.BITWISE_OR, myAtts);
				}

				//-> variable T_XOR_EQUAL expr
				else if (node.getChild(1).getSymbol()==PhpSymbols.T_XOR_EQUAL){
					//Mona Nashaat
					this.cvarOpExp(node, TacOperators.BITWISE_XOR, myAtts);
				}

				//-> variable T_SL_EQUAL expr
				else if (node.getChild(1).getSymbol()==PhpSymbols.T_SL_EQUAL){
					//Mona Nashaat
					this.cvarOpExp(node, TacOperators.SL, myAtts);
				}

				//-> variable T_SR_EQUAL expr
				else if (node.getChild(1).getSymbol()==PhpSymbols.T_SR_EQUAL){
					//Mona Nashaat
					this.cvarOpExp(node, TacOperators.SR, myAtts);
				}

				//-> variable T_INC expr
				else if (node.getChild(1).getSymbol()==PhpSymbols.T_INC){
					postIncDec(node, TacOperators.PLUS, myAtts);
				}

				//-> variable T_DEC expr
				else if (node.getChild(1).getSymbol()==PhpSymbols.T_DEC){
					postIncDec(node, TacOperators.MINUS, myAtts);
				}

			}
			break;
		}

		//-> T_LIST {  assignment_list } = expr
		case PhpSymbols.T_LIST:
		{

			//Mona Nashaat 
			TacAttributes attsExpr = this.expr(node.getChild(5));
			TacAttributes attsList = this.assignment_list(
					node.getChild(2),
					attsExpr.getPlace(), 
					0);

			connect(attsExpr.getCfg(), attsList.getCfg());
			myAtts.setCfg(new Cfg(
					attsExpr.getCfg().getHead(),
					attsList.getCfg().getTail()));

			myAtts.setPlace(attsExpr.getPlace());
			break;


		}
		// -> T_NEW class_name_reference  ctor_arguments
		case PhpSymbols.T_NEW:
		{
			TacAttributes attsCtor = this.ctor_arguments(node.getChild(2));

			// there are two possibilities for static_or_variable_string:
			// - T_STRING (e.g., "new MyClass()")
			// - r_cvar   (e.g., "new $x()")
			// we can only call the constructor for T_STRING
			ParseNode classNameNode = node.getChild(1).getChild(0);
			if (classNameNode.getSymbol() == PhpSymbols.T_STRING) {

				String className = classNameNode.getLexeme().toLowerCase();

				// temporary variable for catching the 
				// constructor's return value (i.e., the object);
				// here, we will use this only as a dummy, since constructors
				// don't really return a value (they return the object);
				// this is why we call myAtts.setPlace(this.objectPlace) in
				// any case (see below)
				TacPlace tempPlace = newTemp();

				Cfg callCfg = this.functionCallHelper(
						className + InternalStrings.methodSuffix, true, null, 
						attsCtor.getActualParamList(), tempPlace, true, node, 
						className, null);

				connect(attsCtor.getCfg(), callCfg.getHead());

				myAtts.setCfg(new Cfg(
						attsCtor.getCfg().getHead(),
						callCfg.getTail()));

				// don't do this, or the assigned variable will not
				// be an object, but null ($x = new MyClass; => $x becomes null)
				// myAtts.setPlace(tempPlace);
				myAtts.setPlace(tempPlace);
				//myAtts.setClassName(className);
			} else {
				// can't resolve the class name
				myAtts.setCfg(attsCtor.getCfg());
				myAtts.setPlace(this.objectPlace);
			}

			break;

		}
		// -> T_CLONE expr
		case PhpSymbols.T_CLONE:
		{
			Clone(node.getParent().getChild(0), node, myAtts);
			break;

		}
		//- >T_INC variable
		case PhpSymbols.T_INC:
		{
			preIncDec(node, TacOperators.PLUS, myAtts);
			break;

		}

		//- >T_DEC variable
		case PhpSymbols.T_DEC:
		{
			preIncDec(node, TacOperators.MINUS, myAtts);
			break;
		}


		//- >expr ....
		case PhpSymbols.expr:
		{
			// -> expr T_BOOLEAN_OR  expr                           
			if (node.getChild(1).getSymbol()==PhpSymbols.T_BOOLEAN_OR){
				this.booleanHelper(node, myAtts, PhpSymbols.T_BOOLEAN_OR);
				break;
			}

			// -> expr T_BOOLEAN_AND  expr                           
			else if (node.getChild(1).getSymbol()==PhpSymbols.T_BOOLEAN_AND){
				this.booleanHelper(node, myAtts, PhpSymbols.T_BOOLEAN_AND);
				break;
			}

			// -> expr T_LOGICAL_OR  expr                           
			else if (node.getChild(1).getSymbol()==PhpSymbols.T_LOGICAL_OR){
				this.booleanHelper(node, myAtts, PhpSymbols.T_LOGICAL_OR);
				break;
			}

			// -> expr T_LOGICAL_AND  expr                           
			else if (node.getChild(1).getSymbol()==PhpSymbols.T_LOGICAL_AND){
				this.booleanHelper(node, myAtts, PhpSymbols.T_LOGICAL_AND);
				break;
			}

			// -> expr T_LOGICAL_XOR  expr                           
			else if (node.getChild(1).getSymbol()==PhpSymbols.T_LOGICAL_XOR){
				// temporary to hold the expression's value
				Variable tempPlace = newTemp();
				TacAttributes atts0 = this.expr(node.getChild(0));
				TacAttributes atts2 = this.expr(node.getChild(2));

				// xor can't result in short-circuit situation:
				// both operands have to be evaluated in each case;
				//
				// when testing, don't forget that "xor" has a very low
				// priority, so 
				// "$c =  $a xor $b " is different from 
				// "$c = ($a xor $b)"

				// nodes for assigning true or false to the temporary
				CfgNode trueNode = new CfgNodeAssignSimple(tempPlace, Constant.TRUE, node);
				CfgNode falseNode = new CfgNodeAssignSimple(tempPlace, Constant.FALSE, node);

				// target node for trueNode and falseNode
				CfgNode emptyNode = new CfgNodeEmpty();
				connect(trueNode, emptyNode);
				connect(falseNode, emptyNode);

				// test first expression
				CfgNode ifNode0 = new CfgNodeIf(
						atts0.getPlace(), 
						Constant.TRUE, 
						TacOperators.IS_EQUAL,
						node.getChild(0));

				// both expression codes can be put in front 
				// of the testing part
				connect(atts0.getCfg(), atts2.getCfg());
				connect(atts2.getCfg(), ifNode0);

				// second expression needs two different tests,
				// depending on the result of the first test
				CfgNode ifNode2WasTrue = new CfgNodeIf(
						atts2.getPlace(), 
						Constant.TRUE, 
						TacOperators.IS_EQUAL,
						node.getChild(2));
				CfgNode ifNode2WasFalse = new CfgNodeIf(
						atts2.getPlace(), 
						Constant.TRUE, 
						TacOperators.IS_EQUAL,
						node.getChild(2));

				// connect test of first expression with corresponding test
				// of second expression
				connect(ifNode0, ifNode2WasTrue, CfgEdge.TRUE_EDGE);
				connect(ifNode0, ifNode2WasFalse, CfgEdge.FALSE_EDGE);

				// connect both tests of second expression with corresponding results
				connect(ifNode2WasTrue, trueNode, CfgEdge.FALSE_EDGE);
				connect(ifNode2WasTrue, falseNode, CfgEdge.TRUE_EDGE);
				connect(ifNode2WasFalse, trueNode, CfgEdge.TRUE_EDGE);
				connect(ifNode2WasFalse, falseNode, CfgEdge.FALSE_EDGE);

				myAtts.setCfg(new Cfg(
						atts0.getCfg().getHead(),
						emptyNode));
				myAtts.setPlace(tempPlace);

				break;
			}

			// -> expr |  expr                           
			else if (node.getChild(1).getSymbol()==PhpSymbols.T_BITWISE_OR){

				this.expOpExp(node, TacOperators.BITWISE_OR, myAtts);
				break;
			}

			// -> expr &  expr                           
			else if (node.getChild(1).getSymbol()==PhpSymbols.T_BITWISE_AND){

				this.expOpExp(node, TacOperators.BITWISE_AND, myAtts);
				break;
			}
			// -> expr T_BITWISE_XOR  expr                           
			else if (node.getChild(1).getSymbol()==PhpSymbols.T_BITWISE_XOR){

				this.expOpExp(node, TacOperators.BITWISE_XOR, myAtts);
				break;
			}
			// -> expr T_POINT  expr                           
			else if (node.getChild(1).getSymbol()==PhpSymbols.T_POINT){

				this.expOpExp(node, TacOperators.CONCAT, myAtts);
				break;
			}

			// -> expr T_PLUS  expr                           
			else if (node.getChild(1).getSymbol()==PhpSymbols.T_PLUS){

				this.expOpExp(node, TacOperators.PLUS, myAtts);
				break;
			}

			// -> expr T_MINUS  expr                           
			else if (node.getChild(1).getSymbol()==PhpSymbols.T_MINUS){

				this.expOpExp(node, TacOperators.MINUS, myAtts);
				break;
			}

			// -> expr T_MULT  expr                           
			else if (node.getChild(1).getSymbol()==PhpSymbols.T_MULT){

				this.expOpExp(node, TacOperators.MULT, myAtts);
				break;
			}

			// -> expr T_DIV  expr                           
			else if (node.getChild(1).getSymbol()==PhpSymbols.T_DIV){

				this.expOpExp(node, TacOperators.DIV, myAtts);
				break;
			}
			// -> expr T_MODULO  expr                           
			else if (node.getChild(1).getSymbol()==PhpSymbols.T_MODULO){

				this.expOpExp(node, TacOperators.MODULO, myAtts);
				break;
			}

			// -> expr T_SL  expr                           
			else if (node.getChild(1).getSymbol()==PhpSymbols.T_SL){

				this.expOpExp(node, TacOperators.SL, myAtts);
				break;
			}

			// -> expr T_SR  expr                           
			else if (node.getChild(1).getSymbol()==PhpSymbols.T_SR){

				this.expOpExp(node, TacOperators.SR, myAtts);
				break;
				// -> expr T_IS_IDENTICAL  expr                           
			}
			else if (node.getChild(1).getSymbol()==PhpSymbols.T_IS_IDENTICAL){

				this.expOpExp(node, TacOperators.IS_IDENTICAL, myAtts);
				break;                        		   
			}
			// -> expr T_IS_NOT_IDENTICAL  expr                           
			else if (node.getChild(1).getSymbol()==PhpSymbols.T_IS_NOT_IDENTICAL){
				this.expOpExp(node, TacOperators.IS_NOT_IDENTICAL, myAtts);
				break;   
			}
			// -> expr T_IS_EQUAL  expr                           
			else if (node.getChild(1).getSymbol()==PhpSymbols.T_IS_EQUAL){
				this.expOpExp(node, TacOperators.IS_EQUAL, myAtts);
				break;
			}
			// -> expr T_IS_NOT_EQUAL  expr                           
			else if (node.getChild(1).getSymbol()==PhpSymbols.T_IS_NOT_EQUAL){
				this.expOpExp(node, TacOperators.IS_NOT_EQUAL, myAtts);
				break;
			}

			// -> expr T_IS_SMALLER  expr                           
			else if (node.getChild(1).getSymbol()==PhpSymbols.T_IS_SMALLER){
				this.expOpExp(node, TacOperators.IS_SMALLER, myAtts);
				break;
			}

			// -> expr T_IS_SMALLER_OR_EQUAL  expr                           
			else if (node.getChild(1).getSymbol()==PhpSymbols.T_IS_SMALLER_OR_EQUAL){
				this.expOpExp(node, TacOperators.IS_SMALLER_OR_EQUAL, myAtts);
				break;
			}

			// -> expr T_IS_GREATER  expr                           
			else if (node.getChild(1).getSymbol()==PhpSymbols.T_IS_GREATER){
				this.expOpExp(node, TacOperators.IS_GREATER, myAtts);
				break;
			}

			// -> expr T_IS_SMALLER_OR_EQUAL  expr                           
			else if (node.getChild(1).getSymbol()==PhpSymbols.T_IS_GREATER_OR_EQUAL){
				this.expOpExp(node, TacOperators.IS_GREATER_OR_EQUAL, myAtts);
				break;
			}

			// -> expr T_INSTANCEOF  class_name_reference                           
			else if (node.getChild(1).getSymbol()==PhpSymbols.T_INSTANCEOF){

				this.expOpExp(node, TacOperators.INSTANCE_OF, myAtts);
				break;		

			}

			// -> expr T_QUESTION 
			else if (node.getChild(1).getSymbol()==PhpSymbols.T_QUESTION){
				// -> expr ? 
				//	expr : 
				//	expr 

				if(node.getChild(2).getSymbol()==PhpSymbols.expr){
					// temporary to hold the overall expression's value
					Variable tempPlace = newTemp();
					TacAttributes attsExprTest = this.expr(node.getChild(0));
					TacAttributes attsExprThen = this.expr(node.getChild(2));
					TacAttributes attsExprElse = this.expr(node.getChild(4));

					CfgNode assignThen = new CfgNodeAssignSimple(
							tempPlace, attsExprThen.getPlace(), node.getChild(2));
					CfgNode assignElse = new CfgNodeAssignSimple(
							tempPlace, attsExprElse.getPlace(), node.getChild(4));

					CfgNode endIfNode = new CfgNodeEmpty();

					CfgNode ifNode = new CfgNodeIf(
							attsExprTest.getPlace(), Constant.TRUE, 
							TacOperators.IS_EQUAL, node.getChild(0));

					connect(attsExprTest.getCfg(), ifNode);

					connect(ifNode, attsExprElse.getCfg(), CfgEdge.FALSE_EDGE);
					connect(ifNode, attsExprThen.getCfg(), CfgEdge.TRUE_EDGE);

					connect(attsExprThen.getCfg(), assignThen);
					connect(attsExprElse.getCfg(), assignElse);

					connect(assignThen, endIfNode);
					connect(assignElse, endIfNode);

					myAtts.setCfg(new Cfg(
							attsExprTest.getCfg().getHead(),
							endIfNode));
					myAtts.setPlace(tempPlace);

					break;
				}
				//->  expr ? : expr
				else {
					// temporary to hold the overall expression's value
					Variable tempPlace = newTemp();
					TacAttributes attsExprTest = this.expr(node.getChild(0));                   
					TacAttributes attsExprElse = this.expr(node.getChild(3));

					CfgNode assignThen = new CfgNodeAssignSimple(
							tempPlace, attsExprTest.getPlace(), node.getChild(2));
					CfgNode assignElse = new CfgNodeAssignSimple(
							tempPlace, attsExprElse.getPlace(), node.getChild(4));

					CfgNode endIfNode = new CfgNodeEmpty();

					CfgNode ifNode = new CfgNodeIf(
							attsExprTest.getPlace(), Constant.TRUE, 
							TacOperators.IS_EQUAL, node.getChild(0));

					connect(attsExprTest.getCfg(), ifNode);

					connect(ifNode, attsExprElse.getCfg(), CfgEdge.FALSE_EDGE);
					connect(ifNode, attsExprTest.getCfg(), CfgEdge.TRUE_EDGE);

					connect(attsExprTest.getCfg(), assignThen);
					connect(attsExprElse.getCfg(), assignElse);

					connect(assignThen, endIfNode);
					connect(assignElse, endIfNode);

					myAtts.setCfg(new Cfg(
							attsExprTest.getCfg().getHead(),
							endIfNode));
					myAtts.setPlace(tempPlace);

					break;
				}
			}

			break;
		}

		//- >T_PLUS expr
		case PhpSymbols.T_PLUS:
		{
			this.opExp(node, TacOperators.PLUS, myAtts);
			break;

		}
		//- >T_MINUS expr
		case PhpSymbols.T_MINUS:
		{
			this.opExp(node, TacOperators.MINUS, myAtts);
			break;
		}
		//- >T_NOT expr
		case PhpSymbols.T_NOT:
		{
			this.opExp(node, TacOperators.NOT, myAtts);
			break;
		}

		//- >T_BITWISE_NOT expr
		case PhpSymbols.T_BITWISE_NOT:
		{
			// if checking for special nodes is enabled, find out
			// if this is a valid special node marker
			boolean special = false;
			String marker = null;
			if (this.specialNodes) {
				try {
					ParseNode constantNode = 
							node.getChild(1).getChild(0).getChild(0).getChild(0);
					if (constantNode.getSymbol() == PhpSymbols.T_STRING &&
							constantNode.getLexeme().startsWith("_")) {
						special = true;
						marker = constantNode.getLexeme();
					}
				} catch (Exception e) {
					// extraction of special node marker failed
				}
			}

			if (!special) {
				this.opExp(node, TacOperators.BITWISE_NOT, myAtts);
			} else {
				// insert appropriate special node for marker
				CfgNode cfgNode = SpecialNodes.get(
						marker, 
						(TacFunction) this.functionStack.getLast(),
						this);
				myAtts.setCfg(new Cfg(cfgNode, cfgNode));
			}

			break;

		}

		//- >( expr )
		case PhpSymbols.T_OPEN_BRACES:
		{
			TacAttributes atts1 = this.expr(node.getChild(1));
			myAtts.setCfg(atts1.getCfg());
			myAtts.setPlace(atts1.getPlace());

			break;

		}

		//- >internal_functions_in_yacc
		case PhpSymbols.internal_functions_in_yacc:
		{
			TacAttributes atts0 = this.internal_functions_in_yacc(firstChild);
			myAtts.setCfg(atts0.getCfg());
			myAtts.setPlace(atts0.getPlace());
			break;

		}

		//- >T_INT_CAST expr 
		case PhpSymbols.T_INT_CAST:
		{
			this.opExp(node, TacOperators.INT_CAST, myAtts);
			break;

		}

		//- >T_DOUBLE_CAST expr 
		case PhpSymbols.T_DOUBLE_CAST:
		{
			this.opExp(node, TacOperators.DOUBLE_CAST, myAtts);
			break;
		}

		//- > T_STRING_CAST expr
		case PhpSymbols.T_STRING_CAST:
		{
			this.opExp(node, TacOperators.STRING_CAST, myAtts);
			break;
		}

		//- >T_ARRAY_CAST expr 
		case PhpSymbols.T_ARRAY_CAST:
		{
			this.opExp(node, TacOperators.ARRAY_CAST, myAtts);
			break;
		}

		//- >T_BOOL_CAST expr 
		case PhpSymbols.T_BOOL_CAST:
		{
			this.opExp(node, TacOperators.BOOL_CAST, myAtts);
			break;
		}

		//- >T_OBJECT_CAST expr 
		case PhpSymbols.T_OBJECT_CAST:
		{
			this.opExp(node, TacOperators.OBJECT_CAST, myAtts);
			break;
		}

		//- >T_UNSET_CAST expr 
		case PhpSymbols.T_UNSET_CAST:
		{
			this.opExp(node, TacOperators.UNSET_CAST, myAtts);
			break;
		}

		//- >T_EXIT exit_expr
		case PhpSymbols.T_EXIT:
		{
			TacAttributes atts1 = this.exit_expr(node.getChild(1));
			myAtts.setPlace(this.voidPlace);
			myAtts.setCfg(atts1.getCfg());
			break;

		}
		//- >T_AT  expr
		case PhpSymbols.T_AT:
		{
			TacAttributes atts1 = this.expr(node.getChild(1));
			myAtts.setPlace(atts1.getPlace());
			myAtts.setCfg(atts1.getCfg());
			break;
		}

		//- >scalar
		case PhpSymbols.scalar:
		{
			TacAttributes atts0 = this.scalar(firstChild);
			myAtts.setCfg(atts0.getCfg());
			myAtts.setPlace(atts0.getPlace());
			break;
		}

		//- > T_ARRAY T_OPEN_BRACES array_pair_list T_CLOSE_BRACES
		case PhpSymbols.T_ARRAY:
		{
			Variable arrayPlace = newTemp();
			TacAttributes attsList = this.array_pair_list(
					node.getChild(2), 
					arrayPlace);

			CfgNode cfgNode = new CfgNodeAssignArray(arrayPlace, node);
			connect(cfgNode, attsList.getCfg());

			myAtts.setCfg(new Cfg(
					cfgNode,
					attsList.getCfg().getTail()));
			myAtts.setPlace(arrayPlace);

			break;
		}


		//- > T_BACKTICK backticks_expr T_BACKTICK
		case PhpSymbols.T_BACKTICK:
		{
			// identical to shell_exec() with double quotes:
			// `echo $a` <=> shell_exec("echo $a")

			TacPlace tempPlace = this.newTemp();
			TacAttributes attsList = this.encaps_list(node.getChild(1));

			EncapsList encapsList = attsList.getEncapsList(); 
			TacAttributes deepList = encapsList.makeAtts(newTemp(), node);

			List<TacActualParam> paramList = new LinkedList<TacActualParam>();
			paramList.add(new TacActualParam(deepList.getPlace(), false));

			Cfg execCallCfg = this.functionCallHelper(
					"shell_exec", false, null, paramList,
					tempPlace, true, node, null, null);

			connect(deepList.getCfg(), execCallCfg.getHead());
			myAtts.setCfg(new Cfg(
					deepList.getCfg().getHead(),
					execCallCfg.getTail()));

			myAtts.setPlace(tempPlace);

			/*
                               List<TacActualParam> paramList = new LinkedList<TacActualParam>();
                               paramList.add(new TacActualParam(attsList.getPlace(), false));

                               Cfg execCallCfg = this.functionCallHelper(
                                   "shell_exec", false, null, paramList,
                                   tempPlace, true, node);

                               connect(attsList.getCfg(), execCallCfg.getHead());
                               myAtts.setCfg(new Cfg(
                                   attsList.getCfg().getHead(),
                                   execCallCfg.getTail()));

                               myAtts.setPlace(tempPlace);
			 */

			break;
		}


		//- > T_PRINT expr
		case PhpSymbols.T_PRINT:
		{
			// treat print like an "echo" with expression value 1
			// (since print() always evaluates to 1)
			TacAttributes atts1 = this.expr(node.getChild(1));
			CfgNode cfgNode = new CfgNodeEcho(atts1.getPlace(), node);
			connect(atts1.getCfg(), cfgNode);

			myAtts.setPlace(new Literal("1"));
			myAtts.setCfg(new Cfg(
					atts1.getCfg().getHead(),
					cfgNode));
			break;
		}

		//- > T_FUNCTION is_reference (parameter_list ) lexical_vars { inner_statement_list }
		case PhpSymbols.T_FUNCTION:
		{
			this.functionHelper(node, 3, 7, myAtts);
			break;
		}
		}


		return myAtts;
	}



	// variable *****************************************************************
	TacAttributes variable(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();        
		ParseNode firstChild = node.getChild(0);      

		switch(firstChild.getSymbol()) {
		case PhpSymbols.base_variable_with_function_calls:

		{

			//->  base_variable_with_function_calls
			if(node.getNumChildren()==1)
			{
				//Mona Nashaat
				myAtts = this.base_variable_with_function_calls(node.getChild(0));
				myAtts.setIsKnownCall(false);				
			}
			// ->base_variable_with_function_calls T_OBJECT_OPERATOR 
			//object_property  method_or_not variable_properties
			else
			{
				//Mona Nashaat
				TacAttributes atts0 = this.base_variable_with_function_calls(node.getChild(0));
				List <TacActualParam> paramlist=null;
				TacPlace tempPlace = this.newTemp();
				if(node.getChild(3).children().size()>=2){
					if(node.getChild(3).getChild(1).getSymbol()==PhpSymbols.function_call_parameter_list)
					{
						if(node.getChild(3).getChild(1).children().size()>=1)
						{
							if(node.getChild(3).getChild(1).getChild(0).getSymbol()==PhpSymbols.non_empty_function_call_parameter_list){
								TacAttributes attsList = this.non_empty_function_call_parameter_list(node.getChild(3).getChild(1).getChild(0));
								paramlist=attsList.getActualParamList();
							}
						}

					}
				}
				TacAttributes atts2 = this.object_property(node.getChild(2), atts0.getPlace().getVariable(), paramlist, atts0.getPlace().getVariable());
				TacAttributes atts3 = this.method_or_not(node.getChild(2),node.getChild(3));
				TacAttributes atts4 = this.variable_properties(node.getChild(4),atts0.getPlace().getVariable(), null, null);

				connect(atts0.getCfg(), atts2.getCfg());
				connect(atts2.getCfg(), atts3.getCfg());
				connect(atts3.getCfg(), atts4.getCfg());
				myAtts.setCfg(new Cfg(
						atts0.getCfg().getHead(),
						atts4.getCfg().getTail()));
				myAtts.setPlace(atts2.getPlace());
				myAtts.setIsKnownCall(atts2.isKnownCall());				
			}

			break;

		}


		}
		return myAtts;
	}   

	// variable_properties *****************************************************************
	TacAttributes variable_properties(ParseNode node, Variable leftPlace, 
			List<TacActualParam> paramList, Variable catchVar) {
		TacAttributes myAtts = new TacAttributes();        
		ParseNode firstChild = node.getChild(0);      

		switch(firstChild.getSymbol()) {
		// ->variable_properties variable_property
		case PhpSymbols.variable_properties:

		{
			//Mona Nashaat :Needs Implementations
			TacAttributes atts0 = this.variable_properties(firstChild,leftPlace, paramList, catchVar);
			TacAttributes atts1 = this.variable_property(node.getChild(1),leftPlace, paramList, catchVar);

			connect(atts0.getCfg(), atts1.getCfg());
			myAtts.setCfg(new Cfg(
					atts0.getCfg().getHead(),
					atts1.getCfg().getTail(),
					atts1.getCfg().getTailEdgeType()));		

			break;

		}
		// -> /* empty */
		case PhpSymbols.T_EPSILON:
		{
			//Mona Nashaat :Needs Implementations

			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			myAtts.setPlace(this.emptyOffsetPlace);
			break;


		}          

		}
		return myAtts;
	}   
	// variable_property *****************************************************************
	TacAttributes variable_property(ParseNode node, Variable leftPlace, 
			List<TacActualParam> paramList, Variable catchVar)
	{

		TacAttributes myAtts = new TacAttributes();        
		ParseNode firstChild = node.getChild(0);      

		switch(firstChild.getSymbol()) {
		// ->T_OBJECT_OPERATOR object_property  method_or_not
		case PhpSymbols.T_OBJECT_OPERATOR:

		{
			//Mona Nashaat :Needs Implementations
			leftPlace = this.memberPlace;

			TacAttributes atts0 = this.object_property(node.getChild(1), 
					leftPlace, paramList, catchVar);
			TacAttributes atts1 = this.method_or_not(node.getChild(1),node.getChild(2));


			connect(atts0.getCfg(), atts1.getCfg());
			myAtts.setCfg(new Cfg(
					atts0.getCfg().getHead(),
					atts1.getCfg().getTail(),
					atts1.getCfg().getTailEdgeType()));		

			break;

		}


		}
		return myAtts;
	}   


	// method_or_not *****************************************************************
	TacAttributes method_or_not(ParseNode FunctionNode,ParseNode node) {
		TacAttributes myAtts = new TacAttributes();        
		ParseNode firstChild = node.getChild(0);      
		TacAttributes atts0 =null;
		switch(firstChild.getSymbol()) {
		// ->T_OPEN_BRACES 
		//	function_call_parameter_list T_CLOSE_BRACES
		case PhpSymbols.T_OPEN_BRACES:

		{
			if (FunctionNode.getSymbol()==PhpSymbols.object_property)
			{
				if (FunctionNode.children().size()>=1){
					if(FunctionNode.getChild(0).getSymbol()==PhpSymbols.object_dim_list)
					{
						if(FunctionNode.getChild(0).children().size()>=1)
						{
							if(FunctionNode.getChild(0).getChild(0).getSymbol()==PhpSymbols.variable_name){
								if(FunctionNode.getChild(0).getChild(0).children().size()>=1){
									if(FunctionNode.getChild(0).getChild(0).getChild(0).getSymbol()==PhpSymbols.T_STRING)
									{
										//Mona Nashaat
										atts0 =this.function_call(FunctionNode);		
										//System.err.println("I am a method , my name is "+ FunctionNode.getChild(0).getChild(0).getChild(0).tokenContent());
									}
								}
							}
						}
					}
				}
			}
			TacAttributes attsList = this.function_call_parameter_list(node.getChild(1));
			if(atts0!=null){
				connect(atts0.getCfg(), attsList.getCfg());
				myAtts.setCfg(new Cfg(
						atts0.getCfg().getHead(),
						attsList.getCfg().getTail()));
			}
			else{
				myAtts.setCfg(attsList.getCfg());
				myAtts.setActualParamList(attsList.getActualParamList());
			}
			break;
		}
		// -> /* empty */
		case PhpSymbols.T_EPSILON:

		{
			//Mona Nashaat :Needs Implementations

			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			myAtts.setPlace(this.emptyOffsetPlace);
			break;



		}          

		}
		return myAtts;
	}   

	// variable_without_objects *****************************************************************
	TacAttributes variable_without_objects(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();        
		ParseNode firstChild = node.getChild(0);      

		switch(firstChild.getSymbol()) {
		// reference_variable
		case PhpSymbols.reference_variable:

		{
			//Mona Nashaat
			TacAttributes attsVar = this.reference_variable(firstChild);
			myAtts.setCfg(attsVar.getCfg());
			myAtts.setPlace(attsVar.getPlace());
			break;



		}
		// -> simple_indirect_reference reference_variable
		case PhpSymbols.simple_indirect_reference:

		{
			//Mona Nashaat
			// "simple_indirect_reference" is one or more $'s
			TacAttributes attsVar = this.reference_variable(node.getChild(1));
			TacAttributes attsRef = this.simple_indirect_reference(
					firstChild, attsVar.getPlace());
			myAtts.setCfg(attsVar.getCfg());
			myAtts.setPlace(attsRef.getPlace());
			break;


		}          

		}
		return myAtts;
	}   

	// static_member *****************************************************************
	TacAttributes static_member(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();        
		ParseNode firstChild = node.getChild(0);      

		switch(firstChild.getSymbol()) {
		// class_name T_DOUBLE_COLON variable_without_objects
		case PhpSymbols.class_name:

		{
			//Mona Nashaat
			Variable var = this.newTemp();
			TacAttributes attsVar = this.variable_without_objects(node.getChild(2));
			var=attsVar.getPlace().getVariable();
			var.SetStatic(true);
			TacAttributes atts0 = this.class_name(firstChild);
			String ClassName= atts0.getEncapsListString();
			var.SetisCustomObject(true);
			var.SetCustomClass(ClassName);

			CfgNode cfgNode = new CfgNodeAssignSimple(var, this.constantsTable.getConstant("NULL"), node.getChild(2));
			Cfg nullCfg = new Cfg(cfgNode, cfgNode);

			myAtts.setCfg(nullCfg);
			myAtts.setPlace(var);
			break;

		}
		// -> reference_variable T_DOUBLE_COLON variable_without_objects
		case PhpSymbols.reference_variable:

		{
			//Mona Nashaat :Needs Implementations


			Variable var = this.newTemp();
			TacAttributes attsVar = this.variable_without_objects(node.getChild(2));
			var=attsVar.getPlace().getVariable();
			var.SetStatic(true);
			TacAttributes atts0 = this.reference_variable(firstChild);
			CfgNode cfgNode = new CfgNodeAssignSimple(var, this.constantsTable.getConstant("NULL"), node.getChild(2));
			Cfg nullCfg = new Cfg(cfgNode, cfgNode);

			myAtts.setCfg(nullCfg);
			myAtts.setPlace(var);
			break;

		}          

		}
		return myAtts;
	}


	// base_variable_with_function_calls *****************************************************************
	TacAttributes base_variable_with_function_calls(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();        
		ParseNode firstChild = node.getChild(0);      

		switch(firstChild.getSymbol()) {
		// base_variable
		case PhpSymbols.base_variable:

		{
			//Mona Nashaat
			TacAttributes attsVar = this.base_variable(firstChild);
			myAtts.setCfg(attsVar.getCfg());
			myAtts.setPlace(attsVar.getPlace());
			break;


		}
		// -> function_call
		case PhpSymbols.function_call:

		{
			//Mona Nashaat 
			TacAttributes atts0 = this.function_call(firstChild);
			myAtts.setCfg(atts0.getCfg());
			myAtts.setPlace(atts0.getPlace());
			break;

		}          

		}
		return myAtts;
	}   
	// base_variable *****************************************************************
	TacAttributes base_variable(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();        
		ParseNode firstChild = node.getChild(0);      

		switch(firstChild.getSymbol()) {
		// reference_variable
		case PhpSymbols.reference_variable:

		{
			//Mona Nashaat
			TacAttributes attsVar = this.reference_variable(firstChild);
			myAtts.setCfg(attsVar.getCfg());
			myAtts.setPlace(attsVar.getPlace());
			break;



		}
		// -> simple_indirect_reference reference_variable
		case PhpSymbols.simple_indirect_reference:

		{
			//Mona Nashaat :Needs Implementations
			// "simple_indirect_reference" is one or more $'s
			TacAttributes attsVar = this.reference_variable(node.getChild(1));
			TacAttributes attsRef = this.simple_indirect_reference(
					firstChild, attsVar.getPlace());
			myAtts.setCfg(attsVar.getCfg());
			myAtts.setPlace(attsRef.getPlace());
			break;
			// System.out.println("simple_indirect_reference Needs Implementations");
			//   break;


		}

		// -> static_member
		case PhpSymbols.static_member:

		{
			//Mona Nashaat :

			TacAttributes attsVar = this.static_member(firstChild);
			myAtts.setCfg(attsVar.getCfg());
			myAtts.setPlace(attsVar.getPlace());
			break;



		}  

		}
		return myAtts;
	}   

	// reference_variable **************************************************************

	// - cfg
	// - place
	TacAttributes reference_variable(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> reference_variable ...
		case PhpSymbols.reference_variable:
		{
			if (node.getChild(1).getSymbol() == PhpSymbols.T_OPEN_RECT_BRACES) {
				// -> reference_variable [ dim_offset ]	

				TacAttributes atts0 = this.reference_variable(firstChild);
				TacAttributes atts2 = this.dim_offset(node.getChild(2));
				myAtts.setPlace(this.makeArrayElementPlace(
						atts0.getPlace(), atts2.getPlace()));
				connect(atts0.getCfg(), atts2.getCfg());
				myAtts.setCfg(new Cfg(
						atts0.getCfg().getHead(),
						atts2.getCfg().getTail()));

			} else {
				// -> reference_variable { expr }		
				// seems to be identical to [dim_offset]

				TacAttributes atts0 = this.reference_variable(firstChild);
				TacAttributes atts2 = this.expr(node.getChild(2));
				myAtts.setPlace(this.makeArrayElementPlace(
						atts0.getPlace(), atts2.getPlace()));
				connect(atts0.getCfg(), atts2.getCfg());
				myAtts.setCfg(new Cfg(
						atts0.getCfg().getHead(),
						atts2.getCfg().getTail()));

			}
			break;
		}

		// -> compound_variable			
		case PhpSymbols.compound_variable:
		{
			myAtts = this.compound_variable(firstChild);
			break;
		}
		}

		return myAtts;
	}

	// dim_offset *****************************************************************

	// - cfg
	// - place
	TacAttributes dim_offset(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {


		// -> empty        
		case PhpSymbols.T_EPSILON:
		{
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			myAtts.setPlace(this.emptyOffsetPlace);
			break;
		}

		// -> expr			
		case PhpSymbols.expr:
		{
			TacAttributes attsExpr = this.expr(firstChild);
			myAtts.setCfg(attsExpr.getCfg());
			myAtts.setPlace(attsExpr.getPlace());
			break;
		}
		}

		return myAtts;
	}


	// compound_variable **************************************************************

	// - cfg
	// - place
	TacAttributes compound_variable(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> T_VARIABLE
		case PhpSymbols.T_VARIABLE:
		{
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			myAtts.setPlace(makePlace(firstChild.getLexeme()));

			break;
		}

		// -> $ { expr }
		case PhpSymbols.T_DOLLAR:
		{
			TacAttributes attsExpr = this.expr(node.getChild(2));
			TacPlace myPlace = this.exprVarHelper(attsExpr.getPlace());
			myAtts.setCfg(attsExpr.getCfg());
			myAtts.setPlace(myPlace);
			break;
		}
		}

		return myAtts;
	}
	// object_property *****************************************************************

	// - cfg
	// - place
	// - isUnknownCall
	TacAttributes object_property(ParseNode node, Variable leftPlace,
			List<TacActualParam> paramList, Variable catchVar) {

		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> object_dim_list
		case PhpSymbols.object_dim_list:
		{
			TacAttributes atts0 = this.object_dim_list(
					firstChild, leftPlace,
					paramList, catchVar);
			myAtts.setCfg(atts0.getCfg());
			myAtts.setPlace(atts0.getPlace());
			myAtts.setIsKnownCall(atts0.isKnownCall());
			break;
		}

		// -> variable_without_objects

		case PhpSymbols.variable_without_objects:
		{
			// something like "$x->$y" or "$x->$y()"

			// very simple approximation
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			myAtts.setPlace(this.memberPlace);
			myAtts.setIsKnownCall(false);
			break;
		}
		}

		return myAtts;
	}

	// object_dim_list *****************************************************************

	// - cfg
	// - place
	// - isUnknownCall
	TacAttributes object_dim_list(ParseNode node, Variable leftPlace,
			List<TacActualParam> paramList, Variable catchVar) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> object_dim_list ...
		case PhpSymbols.object_dim_list:
		{
			// the accessed member variable is an array element;
			// check if our parent nodes told us that this should be a function call:
			if (paramList != null) {
				// this is a very strange case: if it ever happens in a
				// real-world program, you should take a look at it
				// (something weird like "$x->y[1]()"...)
				System.out.println(node.getName());
				throw new RuntimeException("not yet");
			} 

			// something like "$x->y[1]"

			if (node.getChild(1).getSymbol() == PhpSymbols.T_OPEN_RECT_BRACES) {
				// -> object_dim_list T_OPEN_RECT_BRACES dim_offset T_CLOSE_RECT_BRACES
			} else {
				// -> object_dim_list T_OPEN_CURLY_BRACES expr T_CLOSE_CURLY_BRACES
			}

			// very simple approximation
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			myAtts.setPlace(this.memberPlace);
			myAtts.setIsKnownCall(false);
			break;
		}

		// -> variable_name
		case PhpSymbols.variable_name:
		{
			TacAttributes atts0 = this.variable_name(firstChild, leftPlace, 
					paramList, catchVar);
			myAtts.setCfg(atts0.getCfg());
			myAtts.setPlace(atts0.getPlace());
			myAtts.setIsKnownCall(atts0.isKnownCall());
			break;
		}
		}

		if (myAtts.getPlace() == null) {

			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));

			//throw new RuntimeException("HIERHIER2");
		}

		return myAtts;
	}

	// variable_name *******************************************************************

	// - cfg
	// - place
	TacAttributes variable_name(ParseNode node, Variable leftPlace,
			List<TacActualParam> paramList, Variable catchVar) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> T_STRING
		case PhpSymbols.T_STRING:
		{
			if (paramList != null) {
				// a method call

				// the method's name
				//String methodName = firstChild.getLexeme().toLowerCase() + InternalStrings.methodSuffix;
				String methodName = firstChild.getLexeme().toLowerCase();
				// if this call is applied on $this, we can easily extract the classname
				String className = null;
				if (leftPlace.getName().equals("$this")) {
					if(classStack.size()>0){
						className = this.classStack.getLast().getName();
					}
				}
				// make a cfg for this method call
				Cfg callCfg = this.functionCallHelper(
						methodName, true, null, paramList,
						catchVar, true, node, className, (Variable) leftPlace);
				myAtts.setCfg(callCfg);
				myAtts.setPlace(catchVar);
				myAtts.setIsKnownCall(true);

			} else {
				// access to a member variable
				String VarName = firstChild.getLexeme().toLowerCase();
				String className = null;
				if (leftPlace.getName().equals("$this")) {
					if(classStack.size()>0){
						className = this.classStack.getLast().getName();
					}
				}
				// make a cfg for this method call
				myAtts = this.namespace_name(node);
				CfgNode emptyNode = new CfgNodeEmpty();
				myAtts.setCfg(new Cfg(emptyNode, emptyNode));
				myAtts.setPlace(catchVar);
				myAtts.setIsKnownCall(true);

				//CfgNode emptyNode = new CfgNodeEmpty();
				//myAtts.setCfg(new Cfg(emptyNode, emptyNode));
				//myAtts.setPlace(this.memberPlace);
				//myAtts.setIsKnownCall(false);
			}
			break;
		}

		// -> T_OPEN_CURLY_BRACES expr T_CLOSE_CURLY_BRACES
		case PhpSymbols.T_OPEN_CURLY_BRACES:
		{
			// evaluate the expression (such that possible side-effects
			// are handled)
			TacAttributes atts1 = this.expr(node.getChild(1));
			myAtts.setCfg(atts1.getCfg());
			// ...but don't try to find out which member/method is accessed
			myAtts.setPlace(this.memberPlace);
			myAtts.setIsKnownCall(false);
			break;
		}
		}

		return myAtts;
	}
	// simple_indirect_reference ********************************************************

	// - place
	TacAttributes simple_indirect_reference(ParseNode node, TacPlace depPlace) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		if (firstChild.getSymbol() == PhpSymbols.T_DOLLAR) {
			// -> $
			TacPlace myPlace = this.makePlace("${" + depPlace.toString() + "}");
			myPlace.getVariable().setDependsOn(depPlace);
			myAtts.setPlace(myPlace);
		} else {
			// -> simple_indirect_reference $
			TacPlace transPlace = this.makePlace("${" + depPlace.toString() + "}");
			transPlace.getVariable().setDependsOn(depPlace);
			TacAttributes attsRef = this.simple_indirect_reference(
					firstChild, transPlace);
			myAtts.setPlace(attsRef.getPlace());
		}

		return myAtts;
	}

	// assignment_list *****************************************************************

	// - arrayIndex
	// - cfg
	TacAttributes assignment_list(ParseNode node, TacPlace arrayPlace, int arrayIndex) {
		TacAttributes myAtts = new TacAttributes();

		if (node.getNumChildren() == 3) {

			// -> assignment_list , assignment_list_element

			TacAttributes attsList = this.assignment_list(
					node.getChild(0),
					arrayPlace, 
					arrayIndex);
			TacAttributes attsElement = this.assignment_list_element(
					node.getChild(2),
					arrayPlace,
					attsList.getArrayIndex());

			connect(attsList.getCfg(), attsElement.getCfg());
			myAtts.setCfg(new Cfg(
					attsList.getCfg().getHead(),
					attsElement.getCfg().getTail()));

			myAtts.setArrayIndex(attsList.getArrayIndex() + 1);

		} else {

			// -> assignment_list_element
			TacAttributes attsElement = this.assignment_list_element(
					node.getChild(0), 
					arrayPlace, 
					arrayIndex);

			myAtts.setCfg(attsElement.getCfg());
			myAtts.setArrayIndex(arrayIndex + 1);
		}

		return myAtts;
	}


	// assignment_list_element *****************************************************************

	// - cfg
	TacAttributes assignment_list_element(ParseNode node, TacPlace arrayPlace, int arrayIndex) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> variable

		case PhpSymbols.variable:
		{
			TacAttributes attsCvar = this.variable(firstChild);

			TacPlace arrayElementPlace = this.makeArrayElementPlace(
					arrayPlace, new Literal(String.valueOf(arrayIndex)));

			CfgNode cfgNode = new CfgNodeAssignSimple(
					(Variable) attsCvar.getPlace(),
					arrayElementPlace,
					firstChild);

			connect(attsCvar.getCfg(), cfgNode);

			myAtts.setCfg(new Cfg(
					attsCvar.getCfg().getHead(),
					cfgNode));

			break;
		}

		// -> T_LIST ( assignment_list )	
		case PhpSymbols.T_LIST:
		{
			TacPlace arrayElementPlace = this.makeArrayElementPlace(
					arrayPlace, new Literal(String.valueOf(arrayIndex)));

			// recurse
			TacAttributes attsList = this.assignment_list(
					node.getChild(2),
					arrayElementPlace, 
					0);

			myAtts.setCfg(attsList.getCfg());

			break;
		}

		// -> empty
		case PhpSymbols.T_EPSILON:
		{

			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
			break;
		}
		}

		return myAtts;
	}
	// non_empty_array_pair_list *****************************************************************

	// - cfg
	// - arrayIndex: largest existing array index
	// parts are very similar to non_empty_static_array_pair_list
	TacAttributes non_empty_array_pair_list(ParseNode node, Variable arrayPlace) {
		TacAttributes myAtts = new TacAttributes();

		int logId = this.tempId;
		ParseNode firstChild = node.getChild(0);
		switch(firstChild.getSymbol()) {

		// -> non_empty_array_pair_list ...
		case PhpSymbols.non_empty_array_pair_list:
		{
			if (node.getNumChildren() == 3) {

				// -> non_empty_array_pair_list , expr

				TacAttributes attsList = this.non_empty_array_pair_list(firstChild, arrayPlace);
				TacAttributes attsExpr = this.expr(node.getChild(2));

				TacPlace offsetPlace;
				int largestIndex = attsList.getArrayIndex();
				if (largestIndex == -1) {
					offsetPlace = this.emptyOffsetPlace;
				} else {
					largestIndex++;
					offsetPlace = new Literal(String.valueOf(largestIndex));
					myAtts.setArrayIndex(largestIndex);
				}

				CfgNode cfgNode = this.arrayPairListHelper(
						arrayPlace, 
						offsetPlace,
						attsExpr.getPlace(), false, node);

				connect(attsList.getCfg(), attsExpr.getCfg());
				connect(attsExpr.getCfg(), cfgNode);

				myAtts.setCfg(new Cfg(
						attsList.getCfg().getHead(),
						cfgNode));

			} else if (node.getChild(2).getSymbol() == PhpSymbols.T_BITWISE_AND) {

				// -> non_empty_array_pair_list , & variable 
				// Mona Nashaat : Change w_cvar to variable
				TacAttributes attsList = this.non_empty_array_pair_list(firstChild, arrayPlace);
				TacAttributes attsCvar = this.variable(node.getChild(3));

				CfgNode cfgNode = this.arrayPairListHelper(
						arrayPlace, 
						this.emptyOffsetPlace,
						attsCvar.getPlace(), true, node);

				connect(attsList.getCfg(), attsCvar.getCfg());
				connect(attsCvar.getCfg(), cfgNode);

				myAtts.setCfg(new Cfg(
						attsList.getCfg().getHead(),
						cfgNode));

			} else if (node.getChild(4).getSymbol() == PhpSymbols.expr) {

				// -> non_empty_array_pair_list , expr T_DOUBLE_ARROW expr	

				TacAttributes attsList = this.non_empty_array_pair_list(firstChild, arrayPlace);
				TacAttributes attsExpr1 = this.expr(node.getChild(2));
				TacAttributes attsExpr2 = this.expr(node.getChild(4));

				CfgNode cfgNode = this.arrayPairListHelper(
						arrayPlace, attsExpr1.getPlace(), attsExpr2.getPlace(), false, node);

				connect(attsList.getCfg(), attsExpr1.getCfg());
				connect(attsExpr1.getCfg(), attsExpr2.getCfg());
				connect(attsExpr2.getCfg(), cfgNode);

				myAtts.setCfg(new Cfg(
						attsList.getCfg().getHead(),
						cfgNode));

			} else {

				// -> non_empty_array_pair_list , expr T_DOUBLE_ARROW & variable 

				TacAttributes attsList = this.non_empty_array_pair_list(firstChild, arrayPlace);
				TacAttributes attsExpr1 = this.expr(node.getChild(2));
				TacAttributes attsCvar = this.variable(node.getChild(5));

				CfgNode cfgNode = this.arrayPairListHelper(
						arrayPlace, attsExpr1.getPlace(), attsCvar.getPlace(), true, node);

				connect(attsList.getCfg(), attsExpr1.getCfg());
				connect(attsExpr1.getCfg(), attsCvar.getCfg());
				connect(attsCvar.getCfg(), cfgNode);

				myAtts.setCfg(new Cfg(
						attsList.getCfg().getHead(),
						cfgNode));
			}

			break;
		}

		// -> expr ...
		case PhpSymbols.expr:
		{
			if (node.getNumChildren() == 1) {

				// -> expr

				TacAttributes attsExpr = this.expr(firstChild);

				CfgNode cfgNode = this.arrayPairListHelper(
						arrayPlace, new Literal("0"), attsExpr.getPlace(), false, node);

				connect(attsExpr.getCfg(), cfgNode);

				myAtts.setCfg(new Cfg(
						attsExpr.getCfg().getHead(),
						cfgNode));

				myAtts.setArrayIndex(0);

			} else if(node.getChild(2).getSymbol() == PhpSymbols.expr) {

				// -> expr T_DOUBLE_ARROW expr

				TacAttributes attsExpr1 = this.expr(firstChild);
				TacAttributes attsExpr2 = this.expr(node.getChild(2));

				CfgNode cfgNode = this.arrayPairListHelper(
						arrayPlace, attsExpr1.getPlace(), attsExpr2.getPlace(), false, node);

				connect(attsExpr1.getCfg(), attsExpr2.getCfg());
				connect(attsExpr2.getCfg(), cfgNode);

				myAtts.setCfg(new Cfg(
						attsExpr1.getCfg().getHead(),
						cfgNode));

			} else {

				// -> expr T_DOUBLE_ARROW & variable

				TacAttributes attsExpr1 = this.expr(firstChild);
				TacAttributes attsCvar = this.variable(node.getChild(3));

				CfgNode cfgNode = this.arrayPairListHelper(
						arrayPlace, attsExpr1.getPlace(), attsCvar.getPlace(), true, node);

				connect(attsExpr1.getCfg(), attsCvar.getCfg());
				connect(attsCvar.getCfg(), cfgNode);

				myAtts.setCfg(new Cfg(
						attsExpr1.getCfg().getHead(),
						cfgNode));

			}

			break;
		}

		// -> & variable 			
		case PhpSymbols.T_BITWISE_AND:
		{
			TacAttributes attsCvar = this.variable(node.getChild(1));

			CfgNode cfgNode = this.arrayPairListHelper(
					arrayPlace, new Literal("0"), attsCvar.getPlace(), true, node);

			connect(attsCvar.getCfg(), cfgNode);

			myAtts.setCfg(new Cfg(
					attsCvar.getCfg().getHead(),
					cfgNode));

			break;
		}
		}

		this.resetId(logId);
		return myAtts;
	}      

	TacAttributes CreateNewEncaps_list(){
		TacAttributes myAtts = new TacAttributes();		
		myAtts.setEncapsList(new EncapsList());
		return myAtts;
	}

	// encaps_list *****************************************************************

	// - NO cfg
	// - NO place
	// - encapsList
	TacAttributes encaps_list(ParseNode node) {
		TacAttributes myAtts = new TacAttributes();		
		ParseNode firstChild = node.getChild(0);

		//	if (firstChild.getSymbol() == PhpSymbols.T_EPSILON) {
		// -> empty

		//		myAtts.setEncapsList(new EncapsList());
		//		return myAtts;
		//	}


		ParseNode secondChild = node.getChild(1);
		if(node.getChild(0).getSymbol()==PhpSymbols.encaps_list){
			switch(secondChild.getSymbol()) {

			// -> encaps_list encaps_var
			case PhpSymbols.encaps_var:
			{
				TacAttributes attsList = this.encaps_list(firstChild);
				TacAttributes attsVar = this.encaps_var(secondChild);

				EncapsList encapsList = attsList.getEncapsList();
				encapsList.add(attsVar.getPlace(), attsVar.getCfg());
				myAtts.setEncapsList(encapsList);
				break;
			}

			// -> encaps_list T_ENCAPSED_AND_WHITESPACE
			case PhpSymbols.T_ENCAPSED_AND_WHITESPACE:
			{
				this.encapsListHelper(node, myAtts);
				break;
			}
			}

		}
		else{
			switch(firstChild.getSymbol()) {
			// -> encaps_var
			case PhpSymbols.encaps_var:
			{


				//	TacAttributes attsList = this.CreateNewEncaps_list();
				//	TacAttributes attsVar = this.encaps_var(firstChild);

				//	EncapsList encapsList = attsList.getEncapsList();
				//	encapsList.add(attsVar.getPlace(), attsVar.getCfg());
				//	myAtts.setEncapsList(encapsList);


				TacAttributes attsVar = this.encaps_var(firstChild);
				myAtts.setCfg(attsVar.getCfg());
				myAtts.getEncapsList().add(attsVar.getPlace(), attsVar.getCfg());

				break;
			}
			//-> T_ENCAPSED_AND_WHITESPACE encaps_var
			case PhpSymbols.T_ENCAPSED_AND_WHITESPACE:
			{

				//	TacAttributes attsList = this.CreateNewEncaps_list();
				//	TacAttributes attsVar = this.encaps_var(secondChild);

				//	EncapsList encapsList = attsList.getEncapsList();
				//	encapsList.add(attsVar.getPlace(), attsVar.getCfg());
				//	myAtts.setEncapsList(encapsList);


				TacAttributes attsVar = this.encaps_var(secondChild);
				myAtts.setCfg(attsVar.getCfg());
				myAtts.getEncapsList().add(attsVar.getPlace(), attsVar.getCfg());
				break;
			}
			}

		}
		return myAtts;
	}

	// array_pair_list *****************************************************************

	// - cfg
	TacAttributes array_pair_list(ParseNode node, Variable arrayPlace) {
		TacAttributes myAtts = new TacAttributes();

		ParseNode firstChild = node.getChild(0);

		if (firstChild.getSymbol() == PhpSymbols.T_EPSILON) {
			// -> empty 
			CfgNode emptyNode = new CfgNodeEmpty();
			myAtts.setCfg(new Cfg(emptyNode, emptyNode));
		} else {
			// -> non_empty_array_pair_list possible_comma
			TacAttributes atts0 = this.non_empty_array_pair_list(firstChild, arrayPlace);
			myAtts.setCfg(atts0.getCfg());
		}

		return myAtts;
	}

	//here 12345

}



