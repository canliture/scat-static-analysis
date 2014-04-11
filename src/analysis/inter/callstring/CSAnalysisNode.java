package analysis.inter.callstring;

import analysis.TransferFunction;
import analysis.inter.InterAnalysisNode;
import conversion.nodes.CfgNode;

public class CSAnalysisNode
extends InterAnalysisNode {

    public CSAnalysisNode(CfgNode node, TransferFunction tf) {
        super(tf);
    }

}

