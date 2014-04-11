package pixy;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import VisualizePT.GraphViz;
import analysis.dep.DepAnalysis;
import analysis.dep.DepGraph;
import analysis.dep.DepGraphNode;
import analysis.dep.DepGraphNormalNode;
import analysis.dep.DepGraphOpNode;
import analysis.dep.DepGraphSccNode;
import analysis.dep.DepGraphUninitNode;
import analysis.dep.Sink;
import automaton.Automaton;
import automaton.Transition;
import conversion.TacActualParam;
import conversion.TacFunction;
import conversion.TacPlace;
import conversion.nodes.CfgNode;
import conversion.nodes.CfgNodeCallBuiltin;
import conversion.nodes.CfgNodeCallPrep;
import conversion.nodes.CfgNodeCallUnknown;
import sanit.SanitAnalysis;
import transduction.MyTransductions;

// SQL Injection detection
public class SQLAnalysis
extends DepClient {
    
    // flag indicating whether to use transducers or not (are still unstable)
    private boolean useTransducers = false;
    
    //  ********************************************************************************
    
    public SQLAnalysis(DepAnalysis depAnalysis) {
        super(depAnalysis);
        this.getIsTainted = !MyOptions.optionI;
    }
    
    //  ********************************************************************************
    
    public List<Integer> detectVulns() {
        List<Integer> retMe = new LinkedList<Integer>();
        
        try{
            File file = new File(MyOptions.outputHtmlPath+"/Report.html");
            // if file doesnt exists, then create it
            if (!file.exists()) {
                file.createNewFile();
            }
            
            FileWriter fw = new FileWriter(file.getAbsoluteFile(),true);
            PrintWriter bw = new PrintWriter(new BufferedWriter(fw));
            
            if(MyOptions.option_SQLI){
                System.out.println("Creating SQL Injection Analysis Report");
                bw.append("<p class=\"title\">SQL Injection Analysis Report</p>");
                
                // collect sinks
                List<Sink> sinks = this.collectSinks();
                Collections.sort(sinks);
                
                bw.append("<table id=\"rounded-corner\" summary=\"SQL Injection Scan Report\" >"+
                        "<thead>"+
                        "<tr>"+
                        "<th scope=\"col\" class=\"rounded-company\">Vulnerablitiy Class</th>"+
                        "<th scope=\"col\" class=\"rounded-q2\">Vulnerablitiy Type</th>"+
                        "<th scope=\"col\" class=\"rounded-q1\">File Name</th>"+
                        "<th scope=\"col\" class=\"rounded-q1\">Line Number</th>"+
                        "<th scope=\"col\" class=\"rounded-q3\">D.Graph Name</th>"+
                        "</tr>"+
                        "</thead>"+
                        "<tbody>");
                
                List<String> uniquevul= new  LinkedList<String>();
                //System.out.println("SQL Analysis Output");
                //bw.write("SQL Analysis Output");
                //System.out.println("--------------------");
                //bw.write("--------------------");
                //System.out.println();
                //bw.newLine();
                
                String fileName = MyOptions.entryFile.getName();
                
                int graphcount = 0;
                int vulncount = 0;
                for (Sink sink : sinks) {
                    //System.out.println("SQL sink name is "+sink.function.getName());
                    Collection<DepGraph> depGraphs = depAnalysis.getDepGraph(sink);
                    
                    for (DepGraph depGraph : depGraphs) {
                        
                        graphcount++;
                        
                        String graphNameBase = "sql_" + fileName + "_" + graphcount;
                        
                        DepGraph sqlGraph = new DepGraph(depGraph);
                        CfgNode cfgNode =null;
                        if(depGraph.getRoot()!=null)
                        {
                            if(depGraph.getRoot().getCfgNode()!=null)
                            {
                                cfgNode=depGraph.getRoot().getCfgNode();
                            }
                        }
                        if(cfgNode==null){
                            continue;
                        }
                        depGraph.dumpDot(graphNameBase + "_dep", MyOptions.graphPath, depGraph.getUninitNodes(), this.dci);
                        
                        Automaton auto = this.toAutomaton(sqlGraph, depGraph);
                        
                        
                        boolean tainted = false;
                        if (auto.hasDirectlyTaintedTransitions()) {
                            
                            tainted = true;
                        }
                        if (auto.hasIndirectlyTaintedTransitions()) {
                            if (auto.hasDangerousIndirectTaint()) {
                                
                                tainted = true;
                            } else {
                                
                            }
                        }
                        if (!tainted) {
                            
                        } else {
                            vulncount++;
                            
                            retMe.add(cfgNode.getOrigLineno());
                            
                            
                        }
                        
                        // if we have detected a vulnerability, also dump a reduced
                        // SQL dependency graph
                        if (tainted) {
                            if(uniquevul.contains(cfgNode.getLoc()))
                            {
                                vulncount--;
                                
                            }
                            else{
                                
                                DepGraph relevant = this.getRelevant(depGraph);
                                Map<DepGraphUninitNode, InitialTaint> dangerousUninit = this.findDangerousUninit(relevant);
                                if (!dangerousUninit.isEmpty()) {
                                    bw.append("<tr>");
                                    bw.append("<td>SQLI</td>");
                                    
                                    if (dangerousUninit.values().contains(InitialTaint.ALWAYS)) {
                                        //System.out.println("- unconditional");
                                        //bw.write("- unconditional");
                                        bw.append("<td>unconditional</td>");
                                        
                                    } else {
                                        //System.out.println("- conditional on register_globals=on");
                                        //bw.write("- conditional on register_globals=on");
                                        bw.append("<td>conditional on register_globals=on</td>");
                                        
                                    }
                                    relevant.reduceWithLeaves(dangerousUninit.keySet());
                                    Set<? extends DepGraphNode> fillUs;
                                    if (MyOptions.option_V) {
                                        relevant.removeTemporaries();
                                        fillUs = relevant.removeUninitNodes();
                                    } else {
                                        fillUs = dangerousUninit.keySet();
                                    }
                                    bw.append("<td><a href=file:///"+cfgNode.getFileName()+">"+cfgNode.getFileName()+"</a></td>");
                                    bw.append("<td>"+cfgNode.getOrigLineno()+"</td>");
                                    //bw.write("- " + cfgNode.getLoc());
                                    //bw.newLine();
                                    bw.append("<td>sql"+graphcount+"</td>");
                                    //						System.out.println("- Graph: xss" + graphcount);
                                    bw.append("</tr>");
                                    
                                    uniquevul.add(cfgNode.getLoc());
                                    
                                    relevant.dumpDot(graphNameBase + "_min", MyOptions.graphPath, fillUs, this.dci);
                                }
                                else{
                                    vulncount--;
                                }
                            }
                            
                            
                            //bw.newLine();
                            
                        }
                        
                        this.dumpDotAuto(auto, graphNameBase + "_auto", MyOptions.graphPath);
                        
                        if(MyOptions.option_VSQL){
                            
                            try{
                                String input = MyOptions.graphPath+ "/"+graphNameBase + "_dep"+".dot";    // Windows
                                GraphViz gv = new GraphViz();
                                gv.readSource(input);
                                
                                
                                String type = "gif";
                                File out = new File(MyOptions.graphPath+"/"+graphNameBase + "_dep"+"." + type);   // Windows
                                gv.writeGraphToFile( gv.getGraph( gv.getDotSource(), type ), out );
                                
                            }
                            
                            catch(Exception e)
                            {
                                System.out.println("Error in Visualization!!!");
                            }
                        }
                        
                    }
                    
                }
                
                // initial sink count and final graph count may differ (e.g., if some sinks
                // are not reachable)
                if (MyOptions.optionV) {
                    //System.out.println("Total Graph Count: " + graphcount);
                    //bw.write("Total Graph Count: " + graphcount);
                    
                }
                //System.out.println("Total Vuln Count: " + vulncount);
                //bw.write("Total Vuln Count: " + vulncount);
                
                
                //System.out.println();
                //bw.newLine();
                //System.out.println("*****************");
                //bw.write("*****************");
                
                //System.out.println("SQL Analysis END");
                //bw.write("SQL Analysis END");
                
                //System.out.println("*****************");
                //bw.write("*****************");
                
                //System.out.println();
                //bw.newLine();
                bw.append("<tfoot>"+
                        
                        "<tr>"+
                        "<td colspan=\"4\" class=\"rounded-foot-left\" <em> Total Vulnerabilities Count: " + vulncount+"</em></td>"+
                        "<td class=\"rounded-foot-right\">&nbsp;</td>"+
                        "</tr>"+
                        "<tr>"+
                        "<td colspan=\"4\" class=\"rounded-foot-left\"><em>The above data were created using an open source version of Static Code Analyzer \"SCAT\"</em></td>"+
                        "<td class=\"rounded-foot-right\">&nbsp;</td>"+
                        "</tr>"+
                        "</tfoot>");
                
                bw.append("</tbody>");
                bw.append("</table>");
                
                //bw.append("</body>");
                
                //bw.append("</html>");
                System.out.println("SQL Report Created");
                System.out.println("\n");
            }
            else {
                bw.write("<table id=\"rounded-corner\" summary=\"SQL Injection Scan Report\" >"+ "<tbody>");
                
                
                
                bw.write("<tfoot>"+
                        
                        "<tr>"+
                        "<td colspan=\"4\" class=\"rounded-foot-left\"><em>SQL Injection Report Cancelled by user</em></td>"+
                        "<td class=\"rounded-foot-right\">&nbsp;</td>"+
                        "</tr>"+
                        "</tfoot>");
                
                bw.write("</tbody>");
                bw.write("</table>");
                
                
                System.out.println("SQL Injection Report Creation Cancelled");
                System.out.println("\n");
            }
            bw.close();
            
            
        }
        catch(Exception e){
            System.out.println(e.getStackTrace());
        }
        return retMe;
        
    }
    
    //  ********************************************************************************
    
    // alternative to detectVulns;
    // returns those depgraphs for which a vulnerability was detected
    public VulnInfo detectAlternative() {
        
        // will contain depgraphs for which a vulnerability was detected
        VulnInfo retMe = new VulnInfo();
        
        // collect sinks
        List<Sink> sinks = this.collectSinks();
        Collections.sort(sinks);
        
        int graphcount = 0;
        int totalPathCount = 0;
        int basicPathCount = 0;
        int hasCustomSanitCount = 0;
        int customSanitThrownAwayCount = 0;
        for (Sink sink : sinks) {
            
            Collection<DepGraph> depGraphs = depAnalysis.getDepGraph(sink);
            
            for (DepGraph depGraph : depGraphs) {
                
                graphcount++;
                
                DepGraph workGraph = new DepGraph(depGraph);
                Automaton auto = this.toAutomaton(workGraph, depGraph);
                
                boolean tainted = false;
                if (auto.hasDirectlyTaintedTransitions()) {
                    tainted = true;
                }
                if (auto.hasIndirectlyTaintedTransitions()) {
                    if (auto.hasDangerousIndirectTaint()) {
                        tainted = true;
                    }
                }
                if (tainted) {
                    
                    // create a smaller version of this graph
                    DepGraph relevant = this.getRelevant(depGraph);
                    Map<DepGraphUninitNode, InitialTaint> dangerousUninit = this.findDangerousUninit(relevant);
                    relevant.reduceWithLeaves(dangerousUninit.keySet());
                    
                    retMe.addDepGraph(depGraph, relevant);
                }
                
                if (MyOptions.countPaths) {
                    int pathNum = depGraph.countPaths();
                    totalPathCount += pathNum;
                    if (tainted) {
                        basicPathCount += pathNum;
                    }
                }
                
                if (!SanitAnalysis.findCustomSanit(depGraph).isEmpty()) {
                    hasCustomSanitCount++;
                    if (!tainted) {
                        customSanitThrownAwayCount++;
                    }
                }
            }
        }
        
        retMe.setInitialGraphCount(graphcount);
        retMe.setTotalPathCount(totalPathCount);
        retMe.setBasicPathCount(basicPathCount);
        retMe.setCustomSanitCount(hasCustomSanitCount);
        retMe.setCustomSanitThrownAwayCount(customSanitThrownAwayCount);
        return retMe;
        
    }
    
    //  ********************************************************************************
    
    // returns the automaton representation of the given dependency graph;
    // is done by decorating the nodes of the graph with automata bottom-up,
    // and returning the automaton that eventually decorates the root;
    // BEWARE: this also eliminates cycles!
    Automaton toAutomaton(DepGraph depGraph, DepGraph origDepGraph) {
        depGraph.eliminateCycles();
        //depGraph.dumpDot("sqlnocycles", Checker.graphPath, depGraph.getUninitNodes());
        DepGraphNode root = depGraph.getRoot();
        Map<DepGraphNode,Automaton> deco = new HashMap<DepGraphNode,Automaton>();
        Set<DepGraphNode> visited = new HashSet<DepGraphNode>();
        this.decorate(root, deco, visited, depGraph, origDepGraph);
        Automaton rootDeco = deco.get(root).clone();
        // BEWARE: minimization can lead to an automaton that is *less* human-readable
        //rootDeco.minimize();
        return rootDeco;
    }
    
    //  ********************************************************************************
    
    // decorates the given node (and all its successors) with an automaton
    private void decorate(DepGraphNode node, Map<DepGraphNode,Automaton> deco,
            Set<DepGraphNode> visited, DepGraph depGraph, DepGraph origDepGraph) {
        
        visited.add(node);
        
        // if this node has successors, decorate them first (if not done yet)
        List<DepGraphNode> successors = depGraph.getSuccessors(node);
        if (successors != null && !successors.isEmpty()) {
            for (DepGraphNode succ : successors) {
                if (!visited.contains(succ) && deco.get(succ) == null) {
                    decorate(succ, deco, visited, depGraph, origDepGraph);
                }
            }
        }
        
        // now that all successors are decorated, we can decorate this node
        
        Automaton auto = null;
        if (node instanceof DepGraphNormalNode) {
            DepGraphNormalNode normalNode = (DepGraphNormalNode) node;
            if (successors == null || successors.isEmpty()) {
                // this should be a string leaf node
                TacPlace place = normalNode.getPlace();
                if (place.isLiteral()) {
                    auto = Automaton.makeString(place.toString());
                } else {
                    // this case should not happen any longer (now that
                    // we have "uninit" nodes, see below)
                    throw new RuntimeException("SNH: " + place + ", " + normalNode.getCfgNode().getFileName() + "," +
                            normalNode.getCfgNode().getOrigLineno());
                }
            } else {
                // this is an interior node, not a leaf node;
                // the automaton for this node is the union of all the
                // successor automatas
                for (DepGraphNode succ : successors) {
                    if (succ == node) {
                        // a simple loop, can be ignored
                        continue;
                    }
                    Automaton succAuto = deco.get(succ);
                    if (succAuto == null) {
                        throw new RuntimeException("SNH");
                    }
                    if (auto == null) {
                        auto = succAuto;  // cloning not necessary here
                    } else {
                        auto = auto.union(succAuto);
                    }
                }
            }
            
        } else if (node instanceof DepGraphOpNode) {
            auto = this.makeAutoForOp((DepGraphOpNode) node, deco, depGraph);
            
        } else if (node instanceof DepGraphSccNode) {
            
            // for SCC nodes, we generate a coarse string approximation (.* automaton);
            // the taint value depends on the taint value of the successors:
            // if any of the successors is tainted in any way, we make the resulting
            // automaton tainted as well
            
            /*
            * this approach works under the assumption that the SCC contains
            * no "evil" functions (functions that always return a tainted value),
            * and that the direct successors of the SCC are not <uninit> nodes
            * (see there, below);
            * it is primarily based on the fact that SCCs can never contain
            * <uninit> nodes, because <uninit> nodes are always leaf nodes in the dep
            * graph (since they have no successors);
            * under the above assumptions and observations, it is valid to
            * say that the taint value of an SCC node solely depends on
            * the taint values of its successors, and that is exactly what we
            * do here
            *
            */
            
            Transition.Taint taint = Transition.Taint.Untainted;
            for (DepGraphNode succ : successors) {
                if (succ == node) {
                    // a simple loop, should be part of the SCC
                    throw new RuntimeException("SNH");
                }
                Automaton succAuto = deco.get(succ);
                if (succAuto == null) {
                    throw new RuntimeException("SNH");
                }
                if (succAuto.hasTaintedTransitions()) {
                    taint = Transition.Taint.Directly;
                    break;
                }
            }
            //DepGraphUninitNode
            auto = Automaton.makeAnyString(taint);
            
        } else if (node instanceof DepGraphUninitNode) {
            
            // retrieve predecessor
            Set<DepGraphNode> preds = depGraph.getPredecessors(node);
            if (preds.size() != 1) {
                throw new RuntimeException("SNH");
            }
            DepGraphNode pre = preds.iterator().next();
            
            if (pre instanceof DepGraphNormalNode) {
                DepGraphNormalNode preNormal = (DepGraphNormalNode) pre;
                switch (this.initiallyTainted(preNormal.getPlace())) {
                    case ALWAYS:
                    case IFRG:
                        auto = Automaton.makeAnyString(Transition.Taint.Directly);
                        break;
                    case NEVER:
                        auto = Automaton.makeAnyString(Transition.Taint.Untainted);
                        break;
                    default:
                        throw new RuntimeException("SNH");
                }
                
            } else if (pre instanceof DepGraphSccNode) {
                // this case can really happen (e.g.: dcpportal: advertiser.php, forums.php);
                
                // take a look at the "real" predecessors (i.e., take a look "into"
                // the SCC node): if there is exactly one predecessor, namely a
                // DepGraphNormalNode, and if the contained place is initially untainted,
                // there is no danger from here; else: we will have to set it to tainted
                Set<DepGraphNode> origPreds = origDepGraph.getPredecessors(node);
                if (origPreds.size() == 1) {
                    DepGraphNode origPre = origPreds.iterator().next();
                    if (origPre instanceof DepGraphNormalNode) {
                        DepGraphNormalNode origPreNormal = (DepGraphNormalNode) origPre;
                        
                        switch (this.initiallyTainted(origPreNormal.getPlace())) {
                            case ALWAYS:
                            case IFRG:
                                auto = Automaton.makeAnyString(Transition.Taint.Directly);
                                break;
                            case NEVER:
                                auto = Automaton.makeAnyString(Transition.Taint.Untainted);
                                break;
                            default:
                                throw new RuntimeException("SNH");
                        }
                        
                    } else {
                        auto = Automaton.makeAnyString(Transition.Taint.Directly);
                    }
                } else {
                    // conservative decision for this SCC
                    auto = Automaton.makeAnyString(Transition.Taint.Directly);
                }
                
            } else {
                throw new RuntimeException("SNH: " + pre.getClass());
            }
            
        } else {
            throw new RuntimeException("SNH");
        }
        
        if (auto == null) {
            throw new RuntimeException("SNH");
        }
        
        deco.put(node, auto);
        
    }
    
    //  ********************************************************************************
    
    // returns an automaton for the given operation node
    private Automaton makeAutoForOp(DepGraphOpNode node, Map<DepGraphNode,Automaton> deco,
            DepGraph depGraph) {
        
        List<DepGraphNode> successors = depGraph.getSuccessors(node);
        if (successors == null) {
            successors = new LinkedList<DepGraphNode>();
        }
        
        Automaton retMe = null;
        
        String opName = node.getName();
        
        List<Integer> multiList = new LinkedList<Integer>();
        
        if (!node.isBuiltin()) {
            
            // call to function or method for which no definition
            // could be found
            
            CfgNode cfgNodeX = node.getCfgNode();
            if (cfgNodeX instanceof CfgNodeCallUnknown) {
                CfgNodeCallUnknown cfgNode = (CfgNodeCallUnknown) cfgNodeX;
                if (cfgNode.isMethod()) {
                    retMe = Automaton.makeAnyString(Transition.Taint.Untainted);
                } else {
                    retMe = Automaton.makeAnyString(Transition.Taint.Directly);
                }
            } else {
                throw new RuntimeException("SNH");
            }
            
            /*
            CfgNodeCallRet callRet = (CfgNodeCallRet) node.getCfgNode();
            String functionName = callRet.getCallPrepNode().getCallee().getName();
            
            if (functionName.equals(InternalStrings.unknownFunctionName)) {
            
            retMe = Automaton.makeAnyString(Transition.Taint.Directly);
            
            } else if (functionName.equals(InternalStrings.unknownMethodName)) {
            
            retMe = Automaton.makeAnyString(Transition.Taint.Untainted);
            
            } else {
            throw new RuntimeException("SNH");
            }
            */
            
        } else if (opName.equals(".")) {
            
            // CONCAT
            for (DepGraphNode succ : successors) {
                Automaton succAuto = deco.get(succ);
                if (retMe == null) {
                    retMe = succAuto;
                } else {
                    retMe = retMe.concatenate(succAuto);
                }
            }
            
            // WEAK SANITIZATION FUNCTIONS *******************************
            // ops that perform sanitization, but which are insufficient
            // in cases where the output is not enclosed by quotes in an SQL query
            
        } else if (isWeakSanit(opName, multiList)) {
            
            retMe = Automaton.makeAnyString(Transition.Taint.Indirectly);
            
            // STRONG SANITIZATION FUNCTIONS *******************************
            // e.g., ops that return numeric values
            
        } else if (isStrongSanit(opName)) {
            
            retMe = Automaton.makeAnyString(Transition.Taint.Untainted);
            
            // EVIL FUNCTIONS ***************************************
            // take care: if you define evil functions, you must adjust
            // the treatment of SCC nodes in decorate()
            
            // MULTI-OR-DEPENDENCY **********************************
            
        } else if (useTransducers && opName.equals("str_replace")) {
            
            if (successors.size() < 3) {
                throw new RuntimeException("SNH");
            }
            Automaton searchAuto = deco.get(successors.get(0));
            Automaton replaceAuto = deco.get(successors.get(1));
            Automaton subjectAuto = deco.get(successors.get(2));
            
            // search and replace have to be finite strings;
            // extract them
            boolean supported = true;
            String searchString = null;
            String replaceString = null;
            if (searchAuto.isFinite() && replaceAuto.isFinite()) {
                Set<String> searchSet = searchAuto.getFiniteStrings();
                Set<String> replaceSet = replaceAuto.getFiniteStrings();
                if (searchSet.size() != 1 || replaceSet.size() != 1) {
                    supported = false;
                } else {
                    searchString = searchSet.iterator().next();
                    replaceString = replaceSet.iterator().next();
                }
            } else {
                supported = false;
            }
            if (supported == false) {
                throw new RuntimeException("not supported yet");
            }
            
            Automaton transduced = new MyTransductions().str_replace(searchString, replaceString, subjectAuto);
            return transduced;
            
        } else if (isMulti(opName, multiList)) {
            
            Transition.Taint taint = this.multiDependencyAuto(successors, deco, multiList, false);
            retMe = Automaton.makeAnyString(taint);
            
        } else if (isInverseMulti(opName, multiList)) {
            
            Transition.Taint taint = this.multiDependencyAuto(successors, deco, multiList, true);
            retMe = Automaton.makeAnyString(taint);
            
            // CATCH-ALL ********************************************
            
        } else {
            //System.out.println("Unmodeled builtin function (SQL): " + opName);
            
            // conservative decision for operations that have not been
            // modeled yet: .*
            retMe = Automaton.makeAnyString(Transition.Taint.Directly);
        }
        
        return retMe;
    }
    
    //  ********************************************************************************
    
    // checks if the given node (inside the given function) is a sensitive sink;
    // adds an appropriate sink object to the given list if it is a sink
    protected void checkForSink(CfgNode cfgNodeX, TacFunction traversedFunction,
            List<Sink> sinks) {
        
        if (cfgNodeX instanceof CfgNodeCallBuiltin) {
            
            // builtin function sinks
            
            CfgNodeCallBuiltin cfgNode = (CfgNodeCallBuiltin) cfgNodeX;
            String functionName = cfgNode.getFunctionName();
            
            checkForSinkHelper(functionName, cfgNode, cfgNode.getParamList(), traversedFunction, sinks);
            
            /*
            if (functionName.equals("mysql_query")) {
            Sink sink = new Sink(cfgNode, traversedFunction);
            // the first argument is of interest
            List<TacActualParam> paramList = cfgNode.getParamList();
            sink.addSensitivePlace(paramList.get(0).getPlace());
            // add this sink to the list of sensitive sinks
            sinks.add(sink);
            }
            */
            
        } else if (cfgNodeX instanceof CfgNodeCallPrep) {
            
            CfgNodeCallPrep cfgNode = (CfgNodeCallPrep) cfgNodeX;
            String functionName = cfgNode.getFunctionNamePlace().toString();
            
            
            // user-defined custom sinks
            
            checkForSinkHelper(functionName, cfgNode, cfgNode.getParamList(), traversedFunction, sinks);
            
            /*
            } else if (functionName.equals(InternalStrings.unknownMethodName)) {
            // OLD STUFF
            
            // this is a method call, manually add sinks here
            String methodName = cfgNode.getMethodName();
            if (methodName == null) {
            System.out.println("CHECK ME: method name could not be determined");
            System.out.println(cfgNode.getFileName());
            System.out.println(cfgNode.getOrigLineno());
            return;
            }
            
            if (methodName.equals("sql_query")) {
            Sink sink = new Sink(cfgNode, traversedFunction);
            // the first argument is of interest
            List<TacActualParam> paramList = cfgNode.getParamList();
            sink.addSensitivePlace(paramList.get(0).getPlace());
            // add this sink to the list of sensitive sinks
            sinks.add(sink);
            }
            
            if (methodName.equals("query")) {
            Sink sink = new Sink(cfgNode, traversedFunction);
            // the first argument is of interest
            List<TacActualParam> paramList = cfgNode.getParamList();
            sink.addSensitivePlace(paramList.get(0).getPlace());
            // add this sink to the list of sensitive sinks
            sinks.add(sink);
            }
            */
            
            
            /*
            } else if (functionName.equals("enter-name-here")) {
            Sink sink = new Sink(cfgNode, traversedFunction);
            // the first argument is of interest
            List<TacActualParam> paramList = cfgNode.getParamList();
            sink.addSensitivePlace(paramList.get(0).getPlace());
            // add this sink to the list of sensitive sinks
            sinks.add(sink);
            }
            */
            
        } else {
            // not a sink
        }
    }
    
    //  ********************************************************************************
    
    private void checkForSinkHelper(String functionName, CfgNode cfgNode,
            List<TacActualParam> paramList, TacFunction traversedFunction, List<Sink> sinks) {
        
        if (this.dci.getSinks().containsKey(functionName)) {
            Sink sink = new Sink(cfgNode, traversedFunction);
            for (Integer param : this.dci.getSinks().get(functionName)) {
                if (paramList.size() > param) {
                    sink.addSensitivePlace(paramList.get(param).getPlace());
                    // add this sink to the list of sensitive sinks
                    sinks.add(sink);
                }
            }
        } else {
            // not a sink
        }
        
    }
    
    //  ********************************************************************************
    
    private Transition.Taint multiDependencyAuto(List<DepGraphNode> succs,
            Map<DepGraphNode,Automaton> deco, List<Integer> indices, boolean inverse) {
        
        boolean indirectly = false;
        Set<Integer> indexSet = new HashSet<Integer>(indices);
        
        int count = -1;
        for (DepGraphNode succ : succs) {
            count++;
            
            // check if there is a dependency on this successor
            if (inverse) {
                if (indexSet.contains(count)) {
                    continue;
                }
            } else {
                if (!indexSet.contains(count)) {
                    continue;
                }
            }
            
            Automaton succAuto = deco.get(succ);
            if (succAuto == null) {
                throw new RuntimeException("SNH");
            }
            if (succAuto.hasDirectlyTaintedTransitions()) {
                return Transition.Taint.Directly;
            }
            if (succAuto.hasIndirectlyTaintedTransitions()) {
                indirectly = true;
            }
        }
        
        if (indirectly) {
            return Transition.Taint.Indirectly;
        } else {
            // no tainted successors have been found
            return Transition.Taint.Untainted;
        }
    }
    
    //  ********************************************************************************
    
    void dumpDotAuto(Automaton auto, String graphName, String path) {
        
        String filename = graphName + ".dot";
        (new File(path)).mkdir();
        
        try {
            Writer outWriter = new FileWriter(path + "/" + filename);
            String autoDot = auto.toDot();
            outWriter.write(autoDot);
            outWriter.close();
        } catch (IOException e) {
            System.out.println(e.getMessage());
            return;
        }
        
        if (MyOptions.option_P) {
            if (auto.isFinite()) {
                Set<String> finiteStringsSet = auto.getFiniteStrings();
                List<String> finiteStrings = new LinkedList<String>(finiteStringsSet);
                Collections.sort(finiteStrings);
                System.out.println();
                System.out.println("IS FINITE");
                for (String finite : finiteStrings) {
                    System.out.println();
                    System.out.println("Finite BEGIN");
                    System.out.println(finite);
                    System.out.println("Finite END");
                }
            }
            
            System.out.println();
            System.out.println("Prefix BEGIN");
            System.out.println(auto.getCommonPrefix());
            System.out.println("Prefix END");
            System.out.println();
            //System.out.println("as regex: " + auto.toRegExp().toString());
            
            System.out.println("Suffix BEGIN");
            System.out.println(auto.getCommonSuffix());
            System.out.println("Suffix END");
            System.out.println();
        }
        
    }
    
    //  ********************************************************************************
    
    void dumpDotAutoUnique(Automaton auto, String graphName, String path) {
        
        String filename = graphName + ".dot";
        (new File(path)).mkdir();
        
        try {
            Writer outWriter = new FileWriter(path + "/" + filename);
            String autoDot = auto.toDotUnique();
            outWriter.write(autoDot);
            outWriter.close();
        } catch (IOException e) {
            System.out.println(e.getMessage());
            return;
        }
    }
    
    
}
