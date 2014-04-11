package phpParser;


import java.util.*;
import java.io.Serializable;

public final class ParseNode implements Serializable{
    private final int id;
    private static int minFreeId = 0;
    private final String lexeme=null;
    private int lineno =-1;


    private final int symbol;
    private final String name;

    private List<ParseNode> children = new ArrayList<ParseNode>();

    private int     tokenLine    = -1;
    private int     tokenColumn  = -1;
    private String  tokenContent = "";
    private String  tokenFile    = "";
    private boolean isToken = false;

    private ParseNode parent = null;

    public ParseNode(int symbol, String name) {
    	this.id=ParseNode.minFreeId++;
        this.symbol = symbol;
        this.name = name;
        
    }

    public ParseNode(int symbol, String name, String content, int line, int column, String file) {
        this(symbol, name);
        this.tokenContent = content;
        this.tokenLine    = line;
        this.tokenColumn  = column;
        this.isToken      = true;
        this.tokenFile    = file;
        this.lineno=line;
        
    }

    public ParseNode(int symbol, String name, String fileName, String content,
			int line) {    	   	
    	this(symbol, name);    	
         this.tokenContent = content;
         this.tokenLine    = line;
         this.tokenColumn  = -1;
         this.isToken      = true;
         this.tokenFile    = fileName;
         this.lineno=line;
	}

	public ParseNode(int prodNumber, String prodName, String fileName) {
		 this(prodNumber, prodName);
		 this.tokenFile=fileName;

		 
	}

	public int symbol() { return symbol; }
    public String name() { return name; }
    public int line() { return tokenLine; }
    public int column() { return tokenColumn; }
    public String file() { return tokenFile; }
    public String tokenContent() { return tokenContent; }
    public boolean isToken() { return isToken; }
    public List<ParseNode> children() { return children; }

    public String fileStart() {
        Stack<ParseNode> stack = new Stack<ParseNode>();
        stack.push(this);
        while(stack.size() > 0) {
            ParseNode el = stack.pop();
            if (el.isToken) {
                return el.tokenFile;
            } else {
                for (ParseNode child : el.children) {
                    stack.push(child);
                }
            }
        }
        return null;
    }

    public int[] lineColumnStart() {
        int[] res = {-1, -1};

        if (isToken) {
            res[0] = tokenLine;
            res[1] = tokenColumn;
            return res;
        } else {
            for (ParseNode child : children) {
                int[] r = child.lineColumnStart();
                if (r[0] >= 0) {
                    if ((res[0] == -1) || (r[0] < res[0]) || (r[0] == res[0] && r[1] < res[1])) {
                        res = r;
                    }
                }
            }
            return res;
        }
    }
    public int[] lineColumnEnd() {
        int[] res = new int[2];
        if (isToken) {
            res[0] = tokenLine;
            res[1] = tokenColumn+tokenContent.length();
            return res;
        } else {
            for (ParseNode child : children) {
                int[] r = child.lineColumnEnd();
                if ((r[0] > res[0]) || (r[0] == res[0] && r[1] > res[1])) {
                    res = r;
                }
            }
            return res;
        }
    }

    public void parentIs(ParseNode node) {
        parent = node;
    }

    public void newChildrenIs(ParseNode node) {
        children.add(node);
    }

    public void print() {
        print("");
    }
    public void print(String indent) {
        System.out.println(indent+ ": "+name+"("+symbol+") " + (isToken ? "(Token)" : ""));
        if (isToken) {
            System.out.println(indent+ " Line: "+tokenLine+" Content: "+tokenContent);
        } else {
            for (ParseNode child : children) {
                child.print(indent+"  ");
            }
        }
    }
    

 // GET *****************************************************************************

     /**
      * Returns this node's name.
      * 
      * @return  this node's name
      */
     public String getName() {
         return this.name;
     }
     
     /**
      * Returns the name of the scanned file.
      * 
      * @return  the name of the scanned file
      */
     public String getFileName() {
         return this.tokenFile;
     }

     /**
      * Returns this node's symbol number.
      * 
      * @return  this node's symbol number
      */
     public int getSymbol() {        
         return this.symbol;
     } 

     /**
      * Returns this node's children.
      * 
      * @return  this node's children
      */
     public List getChildren() {
         return this.children;
     }

     /**
      * Returns the number of children.
      * 
      * @return  the number of children
      */
     public int getNumChildren() {
         return this.children.size();
     }

     /**
      * Returns the child node at the given index (for non-token nodes only).
      * 
      * @param index     the desired child node's index
      * @return          the child node at the given index
      * @throws UnsupportedOperationException    if this node is a token node
      */
     public ParseNode getChild(int index) {
         if (this.isToken) {
             throw new UnsupportedOperationException("Call to getChild for token node " + this.name);
         } else {
        	 if(index >=children.size())
        	 {  
        		 return null;
        	 }
        		 else
        		 {
        			ParseNode returned =(ParseNode) this.children.get(index);
//        			 System.out.println("***************GETCHILD********************");
//        	         System.out.println("I am a parseNode :"+returned.name+" and my lexeme is"+returned.tokenContent);
//        	         System.out.println("******************************************");
        			 return returned;
        		 }
        	 
         }
         
     }

     /**
      * Returns this node's parent node.
      * 
      * @return  this node's parent node, <code>null</code> if this node
      *          is the root node 
      */
     public ParseNode getParent() {
         return this.parent;
     }

     /**
      * Returns this node's lexeme (for token nodes only).
      * 
      * @return  this node's lexeme
      * @throws UnsupportedOperationException    if this node is not a token node
      */
     public String getLexeme() {
         if (this.isToken) {
            // return this.lexeme;
        	 return this.tokenContent;
         } else {
             throw new UnsupportedOperationException();
         }
     }

     /**
      * Returns this node's line number (for token nodes only).
      * Note that epsilon nodes have line number -2.
      * 
      * @return  this node's line number
      * @throws UnsupportedOperationException    if this node is not a token node
      */
     public int getLineno() {
         if (this.isToken) {
             return this.lineno;
         } else {
             throw new UnsupportedOperationException();
         }
     }

     /**
      * Returns this node's line number if it is a token node,
      * and the line number of the leftmost token node reachable from
      * this node otherwise. Note that epsilon nodes have line number -2.
      * 
      * @return  a reasonable line number
      */
     public int getLinenoLeft() {
         if (this.isToken) {
             return this.lineno;
         } else {
             return this.getChild(0).getLinenoLeft();
         }
     }

     /**
      * Searches the first ancestor that has more than one child and
      * calls this ancestor's getLinenoLeft().
      * 
      * @return  a line number
      */
     /* DELME: doesn't work like that (infinite loop)
     private int getNextLinenoLeft() {
         ParseNode p = this.parent;
         while (parent != null) {
             if (p.getNumChildren() > 1) {
                 ParseNode c = p.getChild(1);
                 return c.getLinenoLeft();
             }
         }
         return -2;
     }
     */

     /**
      * Returns this node's ID.
      * 
      * @return  this node's ID
      */
     public int getId() {
         return this.id;
     }

     
 // SET *****************************************************************************    

     /**
      * Sets this node's parent node.
      * 
      * @param parent  the parse node that shall become this node's parent
      */
     public void setParent(ParseNode parent) {
         this.parent = parent;
     }

 // OTHER ***************************************************************************
     
     /**
      * Adds a node to this node's children and makes this node the 
      * given node's parent (for non-token nodes only).
      * 
      * @param child  the parse node that shall become this node's child
      * @throws UnsupportedOperationException    if this node is a token node
      */
     public ParseNode addChild(ParseNode child) {
         if (this.isToken) {
             throw new UnsupportedOperationException();
         } else {
             this.children.add(child);
             child.setParent(this);
             return child;
         }
     }
 
 
}


