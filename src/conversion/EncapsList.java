package conversion;

import java.util.*;

import phpParser.ParseNode;
import conversion.nodes.*;

// with this class, we generate suitable (and small) cfg's for "encaps_lists"
// (i.e, strings that are delimited by double quotes and that contain variables)
public class EncapsList {

    private List<Object> encapsList;
    
    EncapsList() {
        this.encapsList = new LinkedList<Object>();
    }
    
    void dump() {
        System.out.println("here comes an encaps list:");
        for (Object o : this.encapsList) {
            System.out.println(o);
        }
    }
 
    void add(TacPlace place, Cfg cfg) {
        this.encapsList.add(place);
        this.encapsList.add(cfg);
    }
    
    void add(Literal string) {
        this.encapsList.add(string);
    }
    int Lenght()
    {
    	return this.encapsList.size();
    }
    String ToString()
    {
    	String i="";
    	Iterator<Object> iter = this.encapsList.iterator();
    
        while (iter.hasNext()) {
        	 Object obj = iter.next();
        
        	 if (obj instanceof Literal) {
                 i+=((Literal)obj).toString()+"\\";
         
        	 }
        }
       i=i.substring(0,i.length()-1);
        return i;
    }
    // converts this encapslist into a place and cfg
    // - place
    // - cfg
    TacAttributes makeAtts(Variable temp, ParseNode node) {
        TacAttributes myAtts = new TacAttributes();
        
        CfgNode head = new CfgNodeEmpty();
        CfgNode contd = head;

        // is the temporary variable still empty?
        boolean tempEmpty = true;
        
        Iterator<Object> iter = this.encapsList.iterator();
        Literal lastLiteral = null;
        while (iter.hasNext()) {
            Object obj = iter.next();
            
            if (obj instanceof Literal) {
                Literal lit = (Literal) obj;

                if (lastLiteral != null) {
                    // merge them!
                    lastLiteral = new Literal(lastLiteral.toString() + lit.toString());
                } else {
                    // start new
                    lastLiteral = lit;
                }

            } else if (obj instanceof TacPlace) {
                
                if (lastLiteral != null) {
                    // catch the last literal in a temporary

                    CfgNode cfgNode;
                    if (tempEmpty) {
                        // if the temporary is still empty, we can simply
                        // assign the literal to it
                        cfgNode = new CfgNodeAssignSimple(
                                temp, lastLiteral, node);
                        tempEmpty = false;
                    } else {
                        // if the temporary is non-empty, we have to concat
                        cfgNode = new CfgNodeAssignBinary(
                                temp, temp, lastLiteral, TacOperators.CONCAT, node);
                    }
                    TacConverter.connect(contd, cfgNode);
                    contd = cfgNode;
                    lastLiteral = null;
                }

                // fetch the cfg of this non-literal
                Cfg nextCfg = (Cfg) iter.next();
                
                CfgNode cfgNode;
                if (tempEmpty) {
                    cfgNode = new CfgNodeAssignSimple(
                            temp, (TacPlace) obj, node);
                    tempEmpty = false;
                } else {
                    cfgNode = new CfgNodeAssignBinary(
                            temp, temp, (TacPlace) obj, TacOperators.CONCAT, node);
                }
                TacConverter.connect(contd, nextCfg);
                TacConverter.connect(nextCfg, cfgNode);
                contd = cfgNode;

            } else {
            	//Mona Nashaat
               // throw new RuntimeException("SNH");
            }
        }
        
        if (lastLiteral != null) {
            // some literal is hanging around at the end...
            CfgNode cfgNode;
            if (tempEmpty) {
                cfgNode = new CfgNodeAssignSimple(
                        temp, lastLiteral, node);
                tempEmpty = false;
            } else {
                cfgNode = new CfgNodeAssignBinary(
                        temp, temp, lastLiteral, TacOperators.CONCAT, node);
            }
            TacConverter.connect(contd, cfgNode);
            contd = cfgNode;
            lastLiteral = null;
        }
        
        myAtts.setCfg(new Cfg(head, contd));
        myAtts.setPlace(temp);
        return myAtts;
    }
}
