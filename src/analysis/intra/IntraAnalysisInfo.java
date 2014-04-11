package analysis.intra;

import analysis.AnalysisInfo;
import analysis.AnalysisNode;
import analysis.TransferFunction;
import conversion.nodes.CfgNode;

public class IntraAnalysisInfo 
extends AnalysisInfo {

    public IntraAnalysisInfo() {
        super();
    }
    
    public IntraAnalysisNode getAnalysisNode(CfgNode cfgNode) {
        return (IntraAnalysisNode) this.map.get(cfgNode);
    }

    public TransferFunction getTransferFunction (CfgNode cfgNode) {
        AnalysisNode analysisNode = this.getAnalysisNode(cfgNode);
        return analysisNode.getTransferFunction();
    }

}
