package edu.ucsb.nceas.mdqengine;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.FileWriterWithEncoding;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.dataone.client.v2.CNode;
import org.dataone.client.v2.itk.D1Client;

import edu.ucsb.nceas.mdqengine.model.Check;
import edu.ucsb.nceas.mdqengine.model.Level;
import edu.ucsb.nceas.mdqengine.model.Suite;
import edu.ucsb.nceas.mdqengine.model.Result;
import edu.ucsb.nceas.mdqengine.model.Run;
import edu.ucsb.nceas.mdqengine.model.Status;
import edu.ucsb.nceas.mdqengine.serialize.XmlMarshaller;

public class Aggregator {
	
	public static String[] runColumns = {
		"pid",
		"runId",
		"checkId",
		"checkName",
		"type",
		"environment",
		"level",
		"status",
		"message",
		"value",
		"timestamp"
	};
	
	public static String[] docColumns = {
		"id",
		"formatId",
		"datasource",
		"dataUrl",
		"rightsHolder"
	};
	
	/**
	 * Don't let the user override these parameters when querying, otherwise the batch will be broken
	 */
	public static String[] ignoredParams = {
		"fl",
		"wt"
	};
	
	
	protected Log log = LogFactory.getLog(this.getClass());
		
	private MDQEngine engine = new MDQEngine();
	
	/**
	 * run and graph given suite on a corpus returned by optional query param (solr)
	 * @param args
	 */
	public static void main(String args[]) {
		
		// default query
		String query = "q=formatId:\"eml://ecoinformatics.org/eml-2.1.1\" ";

		try {
			
			// use optional query arg
			if (args.length > 1) {
				query = args[1];
			}

			// parse the query syntax
			List<NameValuePair> params = URLEncodedUtils.parse(query, Charset.forName("UTF-8"));
			
			String xml = IOUtils.toString(new FileInputStream(args[0]), "UTF-8");
			Suite suite = (Suite) XmlMarshaller.fromXml(xml , Suite.class);
			Aggregator aggregator = new Aggregator();
			aggregator.graphBatch(params, suite);

		} catch (Exception e) {
			e.printStackTrace();
		}

	}
	
	public void graphBatch(List<NameValuePair> params, Suite suite) {

		String tabularResult = null;
		try {
			File file = this.runBatch(params, suite);

			tabularResult = IOUtils.toString(new FileInputStream(file), "UTF-8");
			log.debug("Tabular Batch Result: \n" + tabularResult);
			
			// now try doing some analysis
			URL url = this.getClass().getResource("/code/plot.R");
			String script = url.getPath();

			String input = file.getAbsolutePath();
			String output = input + ".pdf";
			
			log.debug("Tabular Batch file here: \n" + input);

			ProcessBuilder pb = new ProcessBuilder("Rscript", "--vanilla", script, input, output);
			Process p = pb.start();
			int ret = p.waitFor();
			log.debug("stdOut:" + IOUtils.toString(p.getInputStream(), "UTF-8"));
			log.debug("stdErr:" + IOUtils.toString(p.getErrorStream(), "UTF-8"));
			
			log.debug("Barplot available here: \n" + output);
					
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
	}
	
	
	/**
	 * Runs suite result
	 * @param query
	 * @param suite
	 * @return
	 * @throws IOException
	 */
	public File runBatch(List<NameValuePair> params, Suite suite) throws IOException {
		
		File file = File.createTempFile("mdqe_batch", ".csv");
		Appendable results = new FileWriterWithEncoding(file, "UTF-8");
		
		// set up our output headers
		List<Object> headerList = new ArrayList<Object>(Arrays.asList(ArrayUtils.addAll(runColumns, docColumns)));
		headerList.add(0, "suiteId");
		CSVFormat format = CSVFormat.DEFAULT.withHeader(headerList.toArray(new String[]{}));
		CSVPrinter csvPrinter = new CSVPrinter(results, format );
		
		CSVParser docsCsv = this.queryCSV(params);
		if (docsCsv != null) {
			Iterator<CSVRecord> recordIter = docsCsv.iterator();
			while (recordIter.hasNext()) {
				CSVRecord docRecord = recordIter.next();
				String id = docRecord.get("id");
				String dataUrl = docRecord.get("dataUrl");

				try {
					InputStream input = new URL(dataUrl).openStream();
					Run run = engine.runSuite(suite, input);
					run.setObjectIdentifier(id);

					// this is a silly step to get the columns back
					String runString = toCSV(run);
					CSVParser runCsv = new CSVParser(new StringReader(runString), CSVFormat.DEFAULT.withHeader());
					Iterator<CSVRecord> runIter = runCsv.iterator();
					while (runIter.hasNext()) {
						CSVRecord runRecord = runIter.next();
						
						// include the suite
						csvPrinter.print(suite.getId());
						
						// print out run information
						Iterator<String> runValueIter = runRecord.iterator();
						while (runValueIter.hasNext()){
							csvPrinter.print(runValueIter.next());
						}
						
						// print out doc information on the same line
						Iterator<String> valueIter = docRecord.iterator();
						while (valueIter.hasNext()){
							csvPrinter.print(valueIter.next());
						}
						
						// add the record delimiter
						csvPrinter.println();
						
					}
					
					runCsv.close();
					
				} catch (Exception e) {
					log.error("Could not run QC on id: " + id, e);
				}
				
			}
			
		}
		
		csvPrinter.close();
		
		return file;
		
	}

	
	public CSVParser queryCSV(List<NameValuePair> pairs) {
		
		try {
			// query system for object
			String solrQuery = "?";
			
			// add the user-supplied parameters, for ones that are allowed
			for (NameValuePair param: pairs) {
				String name = param.getName();
				String value = param.getValue();

				if (!ArrayUtils.contains(ignoredParams, name)) {
					solrQuery += name + "=" + URLEncoder.encode(value, "UTF-8");
					
					// make sure we only include current objects in case the user has not specified such
					if (name.equals("q") && !solrQuery.contains("obsoletedBy")) {
						solrQuery += URLEncoder.encode(" -obsoletedBy:* ", "UTF-8");
					}
				}
			}
			
			
			// include the field list
			solrQuery += "&fl=";
			for (String field: docColumns) {
				solrQuery += field + ",";
			}
			solrQuery.substring(0, solrQuery.length()-1); // get rid of the last comma

			// add additional params if missing
			if (!solrQuery.contains("sort")) {
				solrQuery += "&sort=dateUploaded%20desc";
			}
			if (!solrQuery.contains("rows")) {
				solrQuery += "&rows=10";
			}
			
			// add additional required parameters
			solrQuery += "&&wt=csv";

			log.debug("solrQuery = " + solrQuery);

			// search the index
			CNode node = D1Client.getCN();
			InputStream solrResultStream = node.query(null, "solr", solrQuery);
			
			CSVParser parser = new CSVParser(new InputStreamReader(solrResultStream, "UTF-8"), CSVFormat.DEFAULT.withHeader());
			return parser;
			
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
		return null;
	}
	
	/**
	 * Output the run[s] as a set of CSV records
	 * @param run
	 * @return
	 * @throws IOException 
	 */
	public static String toCSV(Run... runs) throws IOException {
		
		StringBuffer sb = new StringBuffer();
		CSVPrinter csv = CSVFormat.DEFAULT.withHeader(runColumns).print(sb);

		for (Run run: runs) {
			String pid = run.getObjectIdentifier();
			String runId = run.getId();
			Date timestamp = run.getTimestamp();
	
			for (Result result: run.getResult()) {
				
				Check check = result.getCheck();
				String checkId = check.getId();
				String checkName = check.getName();

				String type = check.getType();
				String environment = check.getEnvironment();
				Level level = check.getLevel();
				
				Status status = result.getStatus();
				String message = result.getMessage();
				String value = result.getValue();
				
				// create a csv record from this entry
				csv.printRecord(
						pid,
						runId,
						checkId,
						checkName,
						type,
						environment,
						level,
						status,
						message,
						value,
						timestamp);
				
			}
		}
		
		return sb.toString();
		
	}

}
