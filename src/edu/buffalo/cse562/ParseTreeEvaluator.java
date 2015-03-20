package edu.buffalo.cse562;

import net.sf.jsqlparser.expression.LeafValue;
import net.sf.jsqlparser.expression.StringValue;
import edu.buffalo.cse562.operators.Operator;

public class ParseTreeEvaluator {

	/*
	 * Evaluates a parse-tree
	 */
	public static void evaluate(Operator parseTree) {

		if(parseTree == null) {
			return;
		}
		
		parseTree.initialize();
		
		LeafValue res[];
		/* Keep getting a tuple and displaying it till we exhaust the root operator */
		while((res = parseTree.readOneTuple()) != null) {
			display(res);
		}
	}
	
	public static void display(LeafValue res[]) {
		/* Formatting logic */
		boolean flag = false;
		
		for(int i=0; i<res.length; i++) {
			if(flag)
				System.out.print("|");
			
			if(!flag)
				flag = true;
			
			if(res[i] instanceof StringValue) {
				String str = res[i].toString();
				System.out.print(str.substring(1, str.length() - 1));				
			}
			else {				
				System.out.print(res[i]);
			}
		}
		
		System.out.println();
	}
}
