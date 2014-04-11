package analysis.incdom.tf;

import analysis.LatticeElement;
import analysis.TransferFunction;
import analysis.incdom.IncDomAnalysis;
import analysis.incdom.IncDomLatticeElement;
import conversion.nodes.CfgNode;

// transfer function for adding include dominators
public class IncDomTfAdd
extends TransferFunction {

    private CfgNode cfgNode;
    private IncDomAnalysis incDomAnalysis;
    
// *********************************************************************************    
// CONSTRUCTORS ********************************************************************
// *********************************************************************************     

    public IncDomTfAdd(CfgNode cfgNode, IncDomAnalysis incDomAnalysis) {
        this.cfgNode = cfgNode;
        this.incDomAnalysis = incDomAnalysis;
    }

// *********************************************************************************    
// OTHER ***************************************************************************
// *********************************************************************************  

    public LatticeElement transfer(LatticeElement inX) {

        IncDomLatticeElement in = (IncDomLatticeElement) inX;
        IncDomLatticeElement out = new IncDomLatticeElement(in);
        out.add(this.cfgNode);
        
        // recycle
        out = (IncDomLatticeElement) this.incDomAnalysis.recycle(out);

        return out;
    }
}
