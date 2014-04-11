package pixy;

import java.lang.reflect.*;
import java.util.*;

import sanit.SQLSanitAnalysis;
import sanit.XSSSanitAnalysis;
import analysis.dep.DepAnalysis;
import analysis.inter.AnalysisType;
import analysis.inter.InterWorkList;
import analysis.mod.ModAnalysis;
import conversion.TacConverter;

// helper class to prevent code redundancy and confusion, used by Checker;
// perhaps there are more elegant ways to do this
public class GenericTaintAnalysis {

	private List<DepClient> depClients; 

	public DepAnalysis depAnalysis;

	//  ********************************************************************************

	private GenericTaintAnalysis() {
		this.depClients = new LinkedList<DepClient>();
	}

	//  ********************************************************************************

	private void addDepClient(DepClient depClient) {
		this.depClients.add(depClient);
	}

	//  ********************************************************************************

	// returns null if the given taintString is illegal
	static GenericTaintAnalysis createAnalysis(TacConverter tac, 
			AnalysisType enclosingAnalysis, Checker checker, 
			InterWorkList workList, ModAnalysis modAnalysis) {

		GenericTaintAnalysis gta = new GenericTaintAnalysis();

		gta.depAnalysis = new DepAnalysis(tac,  
				checker.aliasAnalysis, checker.literalAnalysis, enclosingAnalysis,
				workList, modAnalysis);

		try {

			// each of the depclients will get the depAnalysis as parameter
			Class<?>[] argsClass = new Class<?>[] {
					Class.forName("analysis.dep.DepAnalysis")};
			Object[] args = new Object[] {gta.depAnalysis};

			// for each requested depclient...
			for (DepClientInfo dci : MyOptions.getDepClients()) {
				if (!dci.performMe()) {
					continue;
				}
				if(dci.getClassName()=="XSSAnalysis"||dci.getClassName().equalsIgnoreCase("XSSAnalysis")){
					DepAnalysis dep=gta.depAnalysis;
					DepClient depClient=new XSSAnalysis(dep);
					gta.addDepClient(depClient);
				}
				
				if(dci.getClassName()=="SQLAnalysis"||dci.getClassName().equalsIgnoreCase("SQLAnalysis")){
					DepAnalysis dep=gta.depAnalysis;
					DepClient depClient=new SQLAnalysis(dep);
					gta.addDepClient(depClient);
				}
				
				if(dci.getClassName()=="XPathAnalysis"||dci.getClassName().equalsIgnoreCase("XPathAnalysis")){
					DepAnalysis dep=gta.depAnalysis;
					DepClient depClient=new XPathAnalysis(dep);
					gta.addDepClient(depClient);
				}
				
				if(dci.getClassName()=="CommandExecutionAnalysis"||dci.getClassName().equalsIgnoreCase("CommandExecutionAnalysis")){
					DepAnalysis dep=gta.depAnalysis;
					DepClient depClient=new CommandExecutionAnalysis(dep);
					gta.addDepClient(depClient);
				}
				if(dci.getClassName()=="CodeEvaluatingAnalysis"||dci.getClassName().equalsIgnoreCase("CodeEvaluatingAnalysis")){
					DepAnalysis dep=gta.depAnalysis;
					DepClient depClient=new CodeEvaluatingAnalysis(dep);
					gta.addDepClient(depClient);
				}
				
//				if(dci.getClassName()=="sanit.SQLSanitAnalysis"||dci.getClassName().equalsIgnoreCase("sanit.SQLSanitAnalysis")){
//					DepAnalysis dep=gta.depAnalysis;
//					DepClient depClient=new SQLSanitAnalysis(dep);
//					gta.addDepClient(depClient);
//				}
//				if(dci.getClassName()=="sanit.XSSSanitAnalysis"||dci.getClassName().equalsIgnoreCase("sanit.XSSSanitAnalysis")){
//					DepAnalysis dep=gta.depAnalysis;
//					DepClient depClient=new XSSSanitAnalysis(dep);
//					gta.addDepClient(depClient);
//				}
				if(dci.getClassName()=="FileAnalysis"||dci.getClassName().equalsIgnoreCase("FileAnalysis")){
					DepAnalysis dep=gta.depAnalysis;
					DepClient depClient=new FileAnalysis(dep);
					gta.addDepClient(depClient);
				}
				//     Class<?> clientDefinition = Class.forName("pixy."+dci.getClassName());
				//     Constructor constructor = clientDefinition.getConstructor(argsClass);
				//     DepClient depClient = (DepClient) constructor.newInstance(args);
				//     gta.addDepClient(depClient);

			}

		} catch (Exception e) {
			throw new RuntimeException(e);	
		}

		return gta;
	}

	//  ********************************************************************************

	void analyze() {
		this.depAnalysis.analyze();

		// check for unreachable code
		this.depAnalysis.checkReachability();
	}

	//  ********************************************************************************

	List<Integer> detectVulns() {
		List<Integer> retMe = new LinkedList<Integer>();
		for (DepClient depClient : this.depClients) {
			retMe.addAll(depClient.detectVulns());
		}
		return retMe;
	}

	//  ********************************************************************************

	List<DepClient> getDepClients() {
		return this.depClients;
	}

}
