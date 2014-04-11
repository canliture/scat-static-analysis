package pixy;

import VisualizePT.*;
import org.apache.commons.cli.*;
import phpParser.ParseTree;
import conversion.InternalStrings;
import analysis.alias.*;
import analysis.incdom.IncDomAnalysis;
import analysis.inter.AnalysisType;
import analysis.inter.CallGraph;
import analysis.inter.ConnectorComputation;
import analysis.inter.InterWorkList;
import analysis.inter.InterWorkListBetter;
import analysis.inter.InterWorkListOrder;
import analysis.inter.InterWorkListPoor;
import analysis.inter.callstring.CSAnalysis;
import analysis.inter.functional.FunctionalAnalysis;
import analysis.literal.*;
import analysis.mod.ModAnalysis;
import conversion.*;
import java.io.*;
import java.util.*;


public final class Checker {
    
    // enable this switch to make the TacConverter recognize hotspots
    // and other special nodes
    private boolean specialNodes = true;
    
    // required by call-string analyses
    private ConnectorComputation connectorComp = null;
    //private InterWorkListOrder order;
    private InterWorkList workList;
    
    // k-size for call-string analyses
    private int kSize = 1;
    
    // Analyses
    AliasAnalysis aliasAnalysis;
    LiteralAnalysis literalAnalysis;
    public GenericTaintAnalysis gta;
    
    //public static PixyGUI frame;
    IncDomAnalysis incDomAnalysis;
    
    public static void help(Options cliOptions) {
        HelpFormatter helpFormatter = new HelpFormatter();
        helpFormatter.printHelp( "check [options] file", cliOptions);
    }
    
    //  ********************************************************************************
    //  CONSTRUCTOR ********************************************************************
    //  ********************************************************************************
    
    // after calling this constructor and before initializing / analyzing,
    // you can set options by modifying the appropriate member variables
    public Checker(String fileName) {        
        // get entry file
        try {
            MyOptions.entryFile = (new File(fileName)).getCanonicalFile();
        } catch (IOException e) {
            Utils.bail("File not found: " + fileName);
        }        
    }
    
    //  ********************************************************************************
    //  SET ****************************************************************************
    //  ********************************************************************************
    
    // adjust the kSize for call-string analyses
    public void setKSize(int kSize) {
        this.kSize = kSize;
    }
    
    //  ********************************************************************************
    //  OTHERS *************************************************************************
    //  ********************************************************************************
    
    private void readConfig() {
        
        // read config file into props
        String configPath = MyOptions.pixy_home + "/" + MyOptions.configDir + "/config.txt";
        File configFile = new File(configPath);
        Properties props = new Properties();
        try {
            configPath = configFile.getCanonicalPath();
            FileInputStream in = new FileInputStream(configPath);
            props.load(in);
            in.close();
        } catch (FileNotFoundException e) {
            Utils.bail("Can't find configuration file: " + configPath);
        } catch (IOException e) {
            Utils.bail("I/O exception while reading configuration file:" + configPath,
                    e.getMessage());
        }
        props.setProperty(InternalStrings.includePath, "C:\\xampp\\php\\php.exe");
        //MonaNashaat
        props.setProperty(InternalStrings.phpBin, "C:\\xampp\\php\\php.exe");
        
        // read PHP include path from the config file
        MyOptions.includePaths = new LinkedList<File>();
        MyOptions.includePaths.add(new File("."));
        String includePath = props.getProperty(InternalStrings.includePath);
        if (includePath != null) {
            StringTokenizer tokenizer = new StringTokenizer(includePath, ":");
            while (tokenizer.hasMoreTokens()) {
                String pathElement = tokenizer.nextToken();
                //Mona Nashaat
                //File pathElementFile = new File(pathElement);
                File pathElementFile = new File("C:\\xampp\\php");
                if (pathElementFile.isDirectory()) {
                    MyOptions.includePaths.add(pathElementFile);
                } else {
                    System.out.println("Warning: Invalid PHP path directory in config file: " + pathElement);
                }
            }
        }
        
        // location of php binary
        String phpBin = props.getProperty(InternalStrings.phpBin);
        if (phpBin == null) {
            //Utils.bail("Please set " + InternalStrings.phpBin + " in config");
            MyOptions.phpBin = null;
        } else {
            if (!(new File(phpBin)).canExecute()) {
                System.out.println("Warning: Invalid path to PHP binary in config file: " + phpBin);
                MyOptions.phpBin = null;
            } else {
                MyOptions.phpBin = phpBin;
            }
        }
        
        // location of FSA-Utils
        String fsaHome = props.getProperty(InternalStrings.fsaHome);
        if (fsaHome != null) {
            if (!(new File(fsaHome)).exists()) {
                fsaHome = null;
            }
        }
        MyOptions.fsa_home = fsaHome;
        
        // read harmless server variables
        String hsvPath = MyOptions.pixy_home + "/" + MyOptions.configDir + "/harmless_server_vars.txt";
        File hsvFile = new File(hsvPath);
        Properties hsvProps = new Properties();
        try {
            hsvPath = hsvFile.getCanonicalPath();
            FileInputStream in = new FileInputStream(hsvPath);
            hsvProps.load(in);
            in.close();
        } catch (FileNotFoundException e) {
            Utils.bail("Can't find configuration file: " + hsvPath);
        } catch (IOException e) {
            Utils.bail("I/O exception while reading configuration file:" + hsvPath,
                    e.getMessage());
        }
        Enumeration<Object> hsvKeys = hsvProps.keys();
        while (hsvKeys.hasMoreElements()) {
            String hsvElement = (String) hsvKeys.nextElement();
            hsvElement = hsvElement.trim();
            MyOptions.addHarmlessServerIndex(hsvElement);
        }
        
    }
    
    //  initialize *********************************************************************
    
    // taintString: "-y" option, type of taint analysis
    ProgramConverter initialize() {
        
        // *****************
        // PREPARATIONS
        // *****************
        
        // read config file
        readConfig();
        
        // *****************
        // PARSE & CONVERT
        // *****************
        
        // initialize builtin sinks
        MyOptions.initSinks();
        
        // read user-defined custom sinks
        MyOptions.readCustomSinkFiles();
        
        // read builtin function models
        MyOptions.readModelFiles();
        
        // convert the program
        ProgramConverter pcv = new ProgramConverter(
                this.specialNodes, MyOptions.option_A/*, props*/);
        
        // print parse tree in dot syntax
        if (MyOptions.optionP) {
            ParseTree parseTree = pcv.parse(MyOptions.entryFile.getPath());
            Dumper.dumpDot(parseTree, MyOptions.graphPath, "parseTree.dot");
            
            if(MyOptions.option_VPS){            
                try{
                    String input = MyOptions.graphPath+ "/parseTree.dot";    // Windows
                    GraphViz gv = new GraphViz();
                    gv.readSource(input);
                    
                    String type = MyOptions.option_Extension;
                    File out = new File(MyOptions.graphPath+"/ParseTree." + type);   // Windows
                    gv.writeGraphToFile( gv.getGraph( gv.getDotSource(), type ), out );
                    
                    /*
                    System.out.println("*** Printing tokens for file " + MyOptions.entryFile.getPath() + "...");
                    for (Iterator iter = parseTree.leafIterator(); iter.hasNext(); ) {
                        ParseNode leaf = (ParseNode) iter.next();
                        System.out.print(leaf.getName()+" - "+leaf.getLexeme());
                        System.out.print("-"+leaf.getNumChildren());
                        ParseNode parent = leaf.getParent();
                        if(leaf.getChildren().isEmpty()){
                            System.out.print("              My childeren: ");
                        }
                        for(int l=0; l<leaf.getChildren().size();l++)
                        {
                            ParseNode child=leaf.getChild(l);
                            System.out.print(child.getLexeme());
                        }
                        if (parent!=null){
                            if(parent.isToken()){
                                String parentname=parent.getLexeme();
                                System.out.print("      Parent: "+parentname);
                            }
                            else{
                                System.out.print("                is not token");
                            }
                            
                        }
                        else {
                            System.out.print("            has no parent:(");
                        }
                        System.out.println();
                        
                    }*/                    
                }
                catch(Exception e)
                {
                    System.out.println("Error in Visualization!!!");
                }
            }
        }
        pcv.convert();
        TacConverter tac = pcv.getTac();        
        
        if (MyOptions.optionL) {
            if (tac.hasEmptyMain()) {
                System.out.println(MyOptions.entryFile.getPath() + ": library!");
            } else {
                System.out.println(MyOptions.entryFile.getPath() + ": entry point!");
            }
            System.exit(0);
        }
        
        // print maximum number of temporaries
        if (MyOptions.optionM) {
            System.out.println("Maximum number of temporaries: " + tac.getMaxTempId());
        }
        
        // print symbol tables
        if (MyOptions.optionT) {
            Dumper.dump(tac.getSuperSymbolTable(), "Superglobals");
            for (Iterator iter = tac.getUserFunctions().values().iterator(); iter.hasNext(); ) {
                TacFunction function = (TacFunction) iter.next();
                Dumper.dump(function.getSymbolTable(), function.getName());
            }
            Dumper.dump(tac.getConstantsTable());
        }
        
        // print function information
        if (MyOptions.optionF) {
            for (Iterator iter = tac.getUserFunctions().values().iterator(); iter.hasNext(); ) {
                TacFunction function = (TacFunction) iter.next();
                Dumper.dump(function);
            }
        }
        
        // print control flow graphs
        if (MyOptions.optionC || MyOptions.optionD) {
            for (Iterator iter = tac.getUserFunctions().values().iterator(); iter.hasNext(); ) {
                TacFunction function = (TacFunction) iter.next();
                Dumper.dumpDot(function, MyOptions.graphPath, MyOptions.optionD);
            }
            System.exit(0);
        }       
        
        return pcv;
    }
    
    //  analyzeAliases *****************************************************************
    
    // "cleanup" should only be disabled for JUnit tests
    AliasAnalysis analyzeAliases(TacConverter tac, boolean cleanup) {
        
        // ***********************
        // PERFORM ALIAS ANALYSIS
        // ***********************
        
        if (!MyOptions.option_A) {
            this.aliasAnalysis = new DummyAliasAnalysis();
            return this.aliasAnalysis;
        }
        
        ////Checker.report();
        System.out.println("\n*** initializing alias analysis ***\n");
        this.aliasAnalysis = new AliasAnalysis(tac, new FunctionalAnalysis());
        //Checker.report();
        System.out.println("\n*** performing alias analysis ***\n");
        this.aliasAnalysis.analyze();
        //Checker.report();
        if (cleanup) {
            System.out.println("\n*** cleaning up ***\n");
            this.aliasAnalysis.clean();
        }
        //Checker.report();
        System.out.println("\nFinished.");
        
        return this.aliasAnalysis;
        
    }
    
    //  analyzeLiterals ****************************************************************
    
    LiteralAnalysis analyzeLiterals(TacConverter tac) {
        
        // *************************
        // PERFORM LITERAL ANALYSIS
        // *************************
        
        this.analyzeAliases(tac, true);
        
        if (!MyOptions.option_L) {
            this.literalAnalysis = new DummyLiteralAnalysis();
            return this.literalAnalysis;
        }
        
        // this is a call-string analysis and therefore requires previously
        // computed connectors; if this computation hasn't been done yet,
        // do it now
        if (this.connectorComp == null) {
            this.connectorComp = new ConnectorComputation(
                    tac.getAllFunctions(), tac.getMainFunction(), this.kSize);
            connectorComp.compute();
            this.workList = new InterWorkListBetter(new InterWorkListOrder(tac, this.connectorComp));
            // Dumper.dumpFunction2ECS(connectorComp.getFunction2ECS());
            // Dumper.dumpCall2ConFunc(connectorComp.getCall2ConnectorFunction());
        }
        
        //Checker.report();
        //System.out.println("\n*** initializing literal analysis ***\n");
        this.literalAnalysis =
                new LiteralAnalysis(tac, this.aliasAnalysis,
                        new CSAnalysis(this.connectorComp), this.workList);
        //Checker.report();
        //System.out.println("\n*** performing literal analysis ***\n");
        this.literalAnalysis.analyze();
        //Checker.report();
        //System.out.println("\n*** cleaning up ***\n");
        this.literalAnalysis.clean();
        //Checker.report();
        //System.out.println("\nFinished.");
        
        return this.literalAnalysis;
        
    }
    
    //  ********************************************************************************
    
    // - "functional": functional or CS analysis?
    // - "useLiteralAnalysis": use real literal analysis? or rather a dummy?
    //  a dummy literal analysis is MUCH faster (in fact, it doesn't analyze anything),
    //  but can lead to less precise results in if-evaluation and the resolution of
    //  defined constants; can solve easy cases, however (see DummyLiteralAnalysis.java)
    public void analyzeTaint(TacConverter tac, boolean functional) {
        
        // perform literal analysis if necessary; also takes care of alias analysis
        this.analyzeLiterals(tac);
        // ***********************
        // PERFORM TAINT ANALYSIS
        // ***********************
        AnalysisType enclosingAnalysis;
        CallGraph callGraph = null;
        ModAnalysis modAnalysis = null;
        if (functional) {
            //System.out.println("functional analysis!");
            enclosingAnalysis = new FunctionalAnalysis();
            this.workList = new InterWorkListPoor();
        } else {
            if (this.connectorComp == null) {
                this.connectorComp = new ConnectorComputation(
                        tac.getAllFunctions(), tac.getMainFunction(), this.kSize);
                connectorComp.compute();
                this.workList = new InterWorkListBetter(new InterWorkListOrder(tac, this.connectorComp));
                connectorComp.stats(false);
            }
            if (MyOptions.optionV) {
              //  System.out.println("call-string analysis!");
            }
            //System.out.println("STATS:");
            //this.connectorComp.stats();
            enclosingAnalysis = new CSAnalysis(this.connectorComp);
            
            // write called-by relations to file; can be quite useful
            Utils.writeToFile(this.connectorComp.dump(),
                    MyOptions.graphPath + "/" + "/calledby_"  + MyOptions.entryFile.getName() + ".txt");
            
            callGraph = this.connectorComp.getCallGraph();
            if (this.aliasAnalysis instanceof DummyAliasAnalysis) {
                modAnalysis = new ModAnalysis(tac.getAllFunctions(), callGraph);
            }
            
        }
        
        
        //Checker.report();
        this.gta = GenericTaintAnalysis.createAnalysis(tac, enclosingAnalysis,
                this, this.workList, modAnalysis);
        if (this.gta == null) {
            Utils.bail("Please specify a valid type of taint analysis.");
        }
        //Checker.report();
        //System.out.println("\n*** performing taint analysis ***\n");
        gta.analyze();
        
        /*
        Checker.report();
        Checker.report();
        Checker.report();
        */
        
        // DON'T do this here:
        // TaintAnalysis.detectVulns() requires intact context information
        /*
        System.out.println("\n*** cleaning up ***\n");
        this.taintAnalysis.clean();
        */
        
        //System.out.println("\nFinished.");
        
        /*
        Checker.report();
        Checker.report();
        Checker.report();
        */
        
    }
    
    //  analyzeIncDom ******************************************************************
    
    IncDomAnalysis analyzeIncDom(TacFunction function) {
        
        // ***********************************
        // PERFORM INCLUDE DOMINATOR ANALYSIS
        // ***********************************
        
        System.out.println("\n*** initializing incdom analysis ***\n");
        this.incDomAnalysis = new IncDomAnalysis(function);
        System.out.println("\n*** performing incdom analysis ***\n");
        this.incDomAnalysis.analyze();
        
        return this.incDomAnalysis;
    }
    
    //  report *************************************************************************
    
    public static void report() {
        
        System.gc();
        System.gc();
        System.gc();
        
        Runtime rt = Runtime.getRuntime();
        long totalMem = rt.totalMemory();
        long freeMem = rt.freeMemory();
        long usedMem = totalMem - freeMem;
        
        System.out.println("Memory used: " + usedMem);
    }
}