package edu.buffalo.cse562;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Iterator;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.parser.CCJSqlParser;
import net.sf.jsqlparser.parser.ParseException;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectBody;
import net.sf.jsqlparser.statement.select.SelectExpressionItem;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SubJoin;
import net.sf.jsqlparser.statement.select.SubSelect;
import net.sf.jsqlparser.statement.select.Union;
import edu.buffalo.cse562.datastructures.ParseTree;
import edu.buffalo.cse562.exceptions.InsertOnNonEmptyBranchException;
import edu.buffalo.cse562.exceptions.UnsupportedStatementException;
import edu.buffalo.cse562.operators.DistinctOperator;
import edu.buffalo.cse562.operators.GroupByAggregateOperator;
import edu.buffalo.cse562.operators.JoinOperator;
import edu.buffalo.cse562.operators.LimitOperator;
import edu.buffalo.cse562.operators.Operator;
import edu.buffalo.cse562.operators.OrderByOperator;
import edu.buffalo.cse562.operators.ProjectionOperator;
import edu.buffalo.cse562.operators.ScanOperator;
import edu.buffalo.cse562.operators.SelectionOperator;
import edu.buffalo.cse562.operators.UnionOperator;
import edu.buffalo.cse562.schema.ColumnWithType;
import edu.buffalo.cse562.schema.Schema;

public class ParseTreeGenerator {

	// The tables HashMap keeps a mapping of tables to their corresponding schemas
	private static ArrayList<Schema> tables = new ArrayList<Schema>();


	
	
	// Function to find a table within the provided Data Directories
	private static String findFile(ArrayList<String> dataDirs, String tableName) {
		for(String dDirs : dataDirs) {
			File dir = new File(dDirs);
			File files[] = dir.listFiles();
			for(File f : files) {
				if(f.getName().equalsIgnoreCase(tableName+".dat"))
					return f.getAbsolutePath();
			}
		}
		return null;
	}
	
	
	private static Schema findSchema(String fileName) {
		for(int i=0; i<tables.size(); i++) {
			if(tables.get(i).getTableName().equalsIgnoreCase(fileName))
				return tables.get(i);
		}
		
		return null;
	}
	
	
	
	public static ParseTree<Operator> generate(ArrayList<String> dataDirs, File sqlFile) {
		
		ParseTree<Operator> parseTree = new ParseTree<Operator>();
		Statement statement = null;
		
		try {
			
			CCJSqlParser parser = new CCJSqlParser(new FileReader(sqlFile));

			
			/*
			 * Keep looping till we get all statements.
			 * 
			 * Each time the parser gives us an object of a class that implements
			 * the Statement interface.
			 * 
			 * We only concern ourselves with two kinds of Statement - CreateTable & Select
			 */
			while((statement = parser.Statement()) != null) {

				/*
				 * CREATE TABLE Statement
				 * 
				 * We do not actually create a table, the object is to
				 * extract information to generate a schema, that can
				 * be used later to process SELECT queries
				 * 
				 */				
				if(statement instanceof CreateTable) {
					CreateTable cTable = (CreateTable) statement;
					
					String tableName = cTable.getTable().toString();
					String tableFile = findFile(dataDirs, tableName);
					
					if(tableFile == null) {
						System.err.println("Table "+ tableName + " not found in any "
								+ "of the specified directories!");
						System.exit(1);
					}
					
					// Generate the schema for this table
					Schema schema = new Schema(tableName, tableFile);
					@SuppressWarnings("rawtypes")
					Iterator i = cTable.getColumnDefinitions().listIterator();
					
					int k = 0;
					while(i.hasNext()) {
						String colNameAndType[] = i.next().toString().split(" ");
						ColumnWithType c = new ColumnWithType(cTable.getTable(), colNameAndType[0], colNameAndType[1], k);
						k++;
						schema.addColumn(c);
					}
					
					// Store schema for later use
					tables.add(schema);
				}
				

				
				
				/*
				 * SELECT Statement
				 * 
				 * This has a field of type SelectBody, which is an interface
				 * 
				 * SelectBody has two implementing classes, PlainSelect and Union 
				 */				
				else if(statement instanceof Select) {
				
					SelectBody body = ((Select) statement).getSelectBody();
					
					if(body instanceof PlainSelect) {
						
						PlainSelect ps = (PlainSelect) body;
						parseTree = parsePlainSelect(ps);
						

					}
					else if(body instanceof Union) {
						Union union = (Union) body;
						
						@SuppressWarnings("rawtypes")
						Iterator i = union.getPlainSelects().iterator();
						
						parseTree.insertRoot(new UnionOperator(parsePlainSelect((PlainSelect) i.next()).getRoot(), 
								parsePlainSelect((PlainSelect) i.next()).getRoot()));
						while(i.hasNext()) {
							parseTree.insertRoot(new UnionOperator(parseTree.getRoot(), parsePlainSelect((PlainSelect) i.next()).getRoot()));
						}
					}
					
					/*
					 * Unsupported Statement
					 */
					
					else {
						throw new UnsupportedStatementException();
					}
					
					return parseTree;
					
				}
						
				
			}
			
		} catch (FileNotFoundException e) {
			System.err.println("File "+sqlFile+" not found!");
		} catch (ParseException e) {
			System.err.println("Parse Exception");
		} catch (InsertOnNonEmptyBranchException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (UnsupportedStatementException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		return parseTree;
		
	}
	

	@SuppressWarnings("unchecked")
	private static ParseTree<Operator> parsePlainSelect(PlainSelect ps) throws InsertOnNonEmptyBranchException, UnsupportedStatementException {
		
		ParseTree<Operator> parseTree = new ParseTree<Operator>();
	
		//==================FROM CLAUSE=================================
		
		FromItem fi = ps.getFromItem();
		
		
		/*
		 * FromItem is an interface, it has 3 classes -
		 * Table, SubJoin and SubSelect
		 * 
		 * We handle each differently
		 */
		if(fi instanceof Table) {
			Table table = (Table) fi;
			Schema schema = findSchema(table.getName().toString());
			
			// Handle alias if present
			if(fi.getAlias() != null) {
				schema.setTableName(fi.getAlias());
			}
			
			parseTree.insertRoot(new ScanOperator(schema));
		}
		
		if(fi instanceof SubJoin) {
			// TODO
		}
		
		if(fi instanceof SubSelect) {
			// TODO
		}
		
		
		
		
		//====================JOINS====================================

		if(ps.getJoins() != null){
			@SuppressWarnings("rawtypes")
			Iterator i = ps.getJoins().iterator();
			while(i.hasNext()) {
				Join join = (Join) i.next();
				
				ParseTree<Operator> right = new ParseTree<Operator>(
						new ScanOperator(findSchema(join.getRightItem().toString())));
				parseTree.insertRoot(new JoinOperator(parseTree.getLeft().getRoot(),
						right.getRoot()));
				parseTree.insertBranch(right, ParseTree.Side.RIGHT);
				
				if(join.getOnExpression() != null) {
					parseTree.insertRoot(new SelectionOperator(join.getOnExpression(), parseTree.getLeft().getRoot()));										
				}
			}
		}
		
		
		
		
		//======================SELECTION=====================================
		
		if(ps.getWhere() != null) {
			parseTree.insertRoot(new SelectionOperator(ps.getWhere(), parseTree.getLeft().getRoot()));
		
		}						
		
		
		//======================AGGREGATE QUERIES=============================
		
		
		/*
		 * Check if the query is an aggregate query
		 * by seeing if either group by columns list
		 * is null or seeing if there are any
		 * functions in the expression list
		 * 
		 * 
		 */
		Boolean aggregateQuery = false;
		if(ps.getGroupByColumnReferences() != null)
			aggregateQuery = true;
		
		if(!aggregateQuery) {
			@SuppressWarnings("rawtypes")
			Iterator i = ps.getSelectItems().iterator();
			while(i.hasNext()) {
				SelectItem si = (SelectItem) i.next();
				
				if(si instanceof SelectExpressionItem) {
					SelectExpressionItem sei = (SelectExpressionItem) si;
					Expression expr = sei.getExpression();
					if(expr instanceof Function) {
						aggregateQuery = true;
						break;
					}
				}
			}							
		}
		
		
		
		
		
		/*
		 * If its an aggregate query, use the 
		 * group by aggregate operator
		 */
		if(aggregateQuery) {
			
			ArrayList<Column> selectedColumns = new ArrayList<Column>();
			
			if(ps.getGroupByColumnReferences() != null) {
				@SuppressWarnings("rawtypes")
				Iterator i = ps.getGroupByColumnReferences().iterator();
				while(i.hasNext()) {
					selectedColumns.add((Column) i.next());
				}
			}
			
			if(parseTree.getLeft() != null)
				parseTree.insertRoot(new GroupByAggregateOperator(selectedColumns, ps.getSelectItems(), parseTree.getLeft().getRoot()));
			
		}
		
		
		
		//=======================HAVING========================================
		
		if(ps.getHaving() != null) {
			parseTree.insertRoot(new SelectionOperator(ps.getHaving(), parseTree.getLeft().getRoot()));

		}
		
		
		
		//=====================PROJECTION======================================
		
		/*
		 * Only use projection for non-aggregate queries
		 */
		if(!aggregateQuery && ps.getSelectItems() != null) {
			if(!(ps.getSelectItems().get(0) instanceof AllColumns)) {
				parseTree.insertRoot(new ProjectionOperator(ps.getSelectItems(), parseTree.getLeft().getRoot()));
			}
		}
		
		
		
		
		//========================ORDER BY======================================
		
		if(ps.getOrderByElements() != null) {
			@SuppressWarnings("rawtypes")
			Iterator i = ps.getOrderByElements().iterator();
			
			while(i.hasNext()) {
				OrderByElement o = (OrderByElement) i.next();
				parseTree.insertRoot(new OrderByOperator(o.getExpression(), parseTree.getLeft().getRoot()));
			}
		}
		
		
		
		
		
		//=========================DISTINCT=====================================
		
		if(ps.getDistinct() != null) {
			parseTree.insertRoot(new DistinctOperator(parseTree.getLeft().getRoot()));
		}
		
		
		//=======================LIMIT==========================================
		
		if(ps.getLimit() != null) {
			parseTree.insertRoot(new LimitOperator(ps.getLimit().getRowCount(), parseTree.getLeft().getRoot()));
		}
		
		//======================================================================
		
		
		return parseTree;
		
		
	}
	
	
	
	
	
	public static ArrayList<Schema> getTableSchemas() {
		return tables;
	}
}