package pixy;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.*;
import VisualizePT.GraphViz;
import analysis.dep.DepAnalysis;
import analysis.dep.DepGraph;
import analysis.dep.DepGraphNode;
import analysis.dep.DepGraphNormalNode;
import analysis.dep.DepGraphOpNode;
import analysis.dep.DepGraphUninitNode;
import analysis.dep.Sink;
import conversion.TacActualParam;
import conversion.TacFunction;
import conversion.nodes.CfgNode;
import conversion.nodes.CfgNodeCallBuiltin;
import conversion.nodes.CfgNodeCallPrep;
import conversion.nodes.CfgNodeEcho;
import sanit.SanitAnalysis;

// XSS detection
public class XSSAnalysis
extends DepClient {
    
    //  ********************************************************************************
    
    public XSSAnalysis(DepAnalysis depAnalysis) {
        super(depAnalysis);
    }
    
    //  ********************************************************************************
    // how it works:
    // - extracts the "relevant subgraph" (see there for an explanation)
    // - this relevant subgraph has the nice property that we can check for
    //   a vulnerability by simply looking at the remaining <uninit> nodes:
    //   if all these nodes belong to variables that are initially harmless,
    //   everything is OK; otherwise, we have a vulnerability
    public List<Integer> detectVulns() {
        List<Integer> retMe = new LinkedList<Integer>();
        try{
            File file = new File(MyOptions.outputHtmlPath+"/Report.html");
            // if file doesnt exists, then create it
            if (!file.exists()) {
                file.createNewFile();
            }
            
            FileWriter fw = new FileWriter(file.getAbsoluteFile());
            BufferedWriter bw = new BufferedWriter(fw);
            
            
            bw.write("<html>" +
                    "<head>" +
                    "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\"/>"+
                    "<meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge\"/>" +
                    "<title>SCAT V 1.3 Report- XSS</title>"+
                    "<style type=\"text/css\">"+
                    "<!--"+
                    "@import url(\"Style//style.css\");"+
                    "-->"+
                    "</style>"+
                    "</head>"+
                    "<body>");
            
            if(MyOptions.option_XSS){
                System.out.println("Creating XSS Analysis Report");
                bw.write("<p class=\"title\">XSS Analysis Report</p>");
                // collect sinks
                List<Sink> sinks = this.collectSinks();
                Collections.sort(sinks);
                
                bw.write("<table id=\"rounded-corner\" summary=\"XSS Scan Report\" >"+
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
                StringBuilder sink2Graph = new StringBuilder();
                StringBuilder quickReport = new StringBuilder();
                
                String fileName = MyOptions.entryFile.getName();
                
                int graphcount = 0;
                int vulncount = 0;
                for (Sink sink : sinks) {
                    //System.out.println("the sink name is "+sink.function.getName());
                    Collection<DepGraph> depGraphs = depAnalysis.getDepGraph(sink);
                    
                    for (DepGraph depGraph : depGraphs) {
                        
                        graphcount++;
                        
                        String graphNameBase = "xss_" + fileName + "_" + graphcount;
                        
                        if (!MyOptions.optionW) {
                            depGraph.dumpDot(graphNameBase + "_dep", MyOptions.graphPath, this.dci);
                        }
                        //Mona Nashaat
                        //boolean visualize=false;
                        if(MyOptions.option_VXSS){
                            
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
                        
                        // create the relevant subgraph
                        DepGraph relevant = this.getRelevant(depGraph);
                        if(relevant==null)
                            continue;
                        
                        // find those uninit nodes that are dangerous
                        Map<DepGraphUninitNode, InitialTaint> dangerousUninit = this.findDangerousUninit(relevant);
                        
                        // if there are any dangerous uninit nodes...
                        if (!dangerousUninit.isEmpty()) {
                            
                            
                            // make the relevant subgraph smaller
                            relevant.reduceWithLeaves(dangerousUninit.keySet());
                            
                            Set<? extends DepGraphNode> fillUs;
                            if (MyOptions.option_V) {
                                relevant.removeTemporaries();
                                fillUs = relevant.removeUninitNodes();
                            } else {
                                fillUs = dangerousUninit.keySet();
                            }
                            
                            vulncount++;
                            DepGraphNormalNode root = depGraph.getRoot();
                            CfgNode cfgNode = root.getCfgNode();
                            retMe.add(cfgNode.getOrigLineno());
                            //System.out.println("Vulnerability detected!");
                            if(uniquevul.contains(cfgNode.getLoc()))
                            {
                                vulncount--;
                                
                            }
                            else{
                                bw.write("<tr>");
                                bw.write("<td>XSS</td>");
                                
                                //bw.write("Vulnerability detected!");
                                if (dangerousUninit.values().contains(InitialTaint.ALWAYS)) {
                                    //	System.out.println("- unconditional");
                                    bw.write("<td>unconditional</td>");
                                    //	bw.write("- unconditional");
                                } else {
                                    //System.out.println("- conditional on register_globals=on");
                                    bw.write("<td>conditional on register_globals=on</td>");
                                    //bw.write("- conditional on register_globals=on");
                                    
                                }
                                //System.out.println("- " + cfgNode.getLoc());
                                bw.write("<td><a href=file:///"+cfgNode.getFileName()+">"+cfgNode.getFileName()+"</a></td>");
                                bw.write("<td>"+cfgNode.getOrigLineno()+"</td>");
                                //bw.write("- " + cfgNode.getLoc());
                                //bw.newLine();
                                bw.write("<td>xss"+graphcount+"</td>");
                                //						System.out.println("- Graph: xss" + graphcount);
                                bw.write("</tr>");
                                
                                uniquevul.add(cfgNode.getLoc());
                                
                                relevant.dumpDot(graphNameBase + "_min", MyOptions.graphPath, fillUs, this.dci);
                                //						System.out.println();
                            }
                            if (MyOptions.optionW) {
                                sink2Graph.append(sink.getLineNo());
                                sink2Graph.append(":");
                                sink2Graph.append(graphNameBase + "_min");
                                sink2Graph.append("\n");
                                
                                quickReport.append("Line ");
                                quickReport.append(sink.getLineNo());
                                quickReport.append("\nSources:\n");
                                for (DepGraphNode leafX : relevant.getLeafNodes()) {
                                    quickReport.append("  ");
                                    if (leafX instanceof DepGraphNormalNode) {
                                        DepGraphNormalNode leaf = (DepGraphNormalNode) leafX;
                                        quickReport.append(leaf.getLine());
                                        quickReport.append(" : ");
                                        quickReport.append(leaf.getPlace());
                                    } else if (leafX instanceof DepGraphOpNode) {
                                        DepGraphOpNode leaf = (DepGraphOpNode) leafX;
                                        quickReport.append(leaf.getLine());
                                        quickReport.append(" : ");
                                        quickReport.append(leaf.getName());
                                    }
                                    quickReport.append("\n");
                                }
                                quickReport.append("\n");
                            }
                        }
                    }
                }
                
                // initial sink count and final graph count may differ (e.g., if some sinks
                // are not reachable)
                if (MyOptions.optionV) {
                    System.out.println("Total Graph Count: " + graphcount);
                }
                
                bw.write("<tfoot>"+
                        
                        "<tr>"+
                        "<td colspan=\"4\" class=\"rounded-foot-left\" <em> Total Vulnerabilities Count: " + vulncount+"</em></td>"+
                        "<td class=\"rounded-foot-right\">&nbsp;</td>"+
                        "</tr>"+
                        "<tr>"+
                        "<td colspan=\"4\" class=\"rounded-foot-left\"><em>The above data were created using an open source version of Static Code Analyzer \"SCAT\"</em></td>"+
                        "<td class=\"rounded-foot-right\">&nbsp;</td>"+
                        "</tr>"+
                        "</tfoot>");
                
                bw.write("</tbody>");
                bw.write("</table>");
                System.out.println("XSS Report Created");
                System.out.println("\n");
                
            }
            else {
                bw.write("<table id=\"rounded-corner\" summary=\"XSS Scan Report\" >"+ "<tbody>");
                
                
                
                bw.write("<tfoot>"+
                        
                        "<tr>"+
                        "<td colspan=\"4\" class=\"rounded-foot-left\"><em>XSS Report Cancelled by user</em></td>"+
                        "<td class=\"rounded-foot-right\">&nbsp;</td>"+
                        "</tr>"+
                        "</tfoot>");
                
                bw.write("</tbody>");
                bw.write("</table>");
                
                
                System.out.println("XSS Report Creation Cancelled");
                System.out.println("\n");
            }
            
            
            if (MyOptions.optionW) {
                //I commented this "Mona Nashaat"
                //Utils.writeToFile(sink2Graph.toString(), MyOptions.graphPath + "/xssSinks2Urls.txt");
                //Utils.writeToFile(quickReport.toString(), MyOptions.graphPath + "/xssQuickReport.txt");
            }
            bw.close();
        }
        catch(Exception e){
            
            System.err.println(e.getStackTrace());
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
                
                // create the relevant subgraph
                DepGraph relevant = this.getRelevant(depGraph);
                
                // find those uninit nodes that are dangerous
                Map<DepGraphUninitNode, InitialTaint> dangerousUninit = this.findDangerousUninit(relevant);
                
                // if there are any dangerous uninit nodes...
                boolean tainted = false;
                if (!dangerousUninit.isEmpty()) {
                    tainted = true;
                    
                    // make the relevant subgraph smaller
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
    
    // checks if the given node (inside the given function) is a sensitive sink;
    // adds an appropriate sink object to the given list if it is a sink
    protected void checkForSink(CfgNode cfgNodeX, TacFunction traversedFunction,
            List<Sink> sinks) {
        
        if (cfgNodeX instanceof CfgNodeEcho) {
            
            // echo() or print()
            CfgNodeEcho cfgNode = (CfgNodeEcho) cfgNodeX;
            
            // create sink object for this node
            Sink sink = new Sink(cfgNode, traversedFunction);
            sink.addSensitivePlace(cfgNode.getPlace());
            
            // add it to the list of sensitive sinks
            sinks.add(sink);
            
        } else if (cfgNodeX instanceof CfgNodeCallBuiltin) {
            
            // builtin function sinks
            
            CfgNodeCallBuiltin cfgNode = (CfgNodeCallBuiltin) cfgNodeX;
            String functionName = cfgNode.getFunctionName();
            
            checkForSinkHelper(functionName, cfgNode, cfgNode.getParamList(), traversedFunction, sinks);
            
        } else if (cfgNodeX instanceof CfgNodeCallPrep) {
            
            CfgNodeCallPrep cfgNode = (CfgNodeCallPrep) cfgNodeX;
            String functionName = cfgNode.getFunctionNamePlace().toString();
            
            // user-defined custom sinks
            
            checkForSinkHelper(functionName, cfgNode, cfgNode.getParamList(), traversedFunction, sinks);
            
        } else {
            // not a sink
        }
    }
    
    //  ********************************************************************************
    
    // LATER: this method looks very similar in all client analyses;
    // possibility to reduce code redundancy
    private void checkForSinkHelper(String functionName, CfgNode cfgNode,
            List<TacActualParam> paramList, TacFunction traversedFunction, List<Sink> sinks) {
        
        if (this.dci.getSinks().containsKey(functionName)) {
            Sink sink = new Sink(cfgNode, traversedFunction);
            Set<Integer> indexList = this.dci.getSinks().get(functionName);
            if (indexList == null) {
                // special treatment is necessary here
                if (functionName.equals("printf"))  {
                    // none of the arguments to printf must be tainted
                    for (Iterator iter = paramList.iterator(); iter.hasNext();) {
                        TacActualParam param = (TacActualParam) iter.next();
                        sink.addSensitivePlace(param.getPlace());
                    }
                    sinks.add(sink);
                }
            } else {
                for (Integer index : indexList) {
                    if (paramList.size() > index) {
                        sink.addSensitivePlace(paramList.get(index).getPlace());
                        // add this sink to the list of sensitive sinks
                        sinks.add(sink);
                    }
                }
            }
        } else {
            // not a sink
        }
        
    }
    
}
