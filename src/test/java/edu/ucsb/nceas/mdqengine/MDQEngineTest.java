package edu.ucsb.nceas.mdqengine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.InputStream;
import java.net.URL;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.dataone.client.v2.CNode;
import org.dataone.client.v2.itk.D1Client;
import org.dataone.configuration.Settings;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import edu.ucsb.nceas.mdqengine.model.Check;
import edu.ucsb.nceas.mdqengine.model.Recommendation;
import edu.ucsb.nceas.mdqengine.model.RecommendationFactory;
import edu.ucsb.nceas.mdqengine.model.Result;
import edu.ucsb.nceas.mdqengine.model.Run;
import edu.ucsb.nceas.mdqengine.processor.XMLDialect;
import edu.ucsb.nceas.mdqengine.serialize.JsonMarshaller;
import edu.ucsb.nceas.mdqengine.serialize.XmlMarshaller;

public class MDQEngineTest {
	
	protected Log log = LogFactory.getLog(this.getClass());

	private String id = "doi:10.5063/AA/tao.1.1";

	private Recommendation recommendation = null;
	
	@Before
	public void setUpRecommendation() {
		recommendation = RecommendationFactory.getMockRecommendation();
	}
	
	@Test
	public void testMetadataCheck() {
		try {
			
			// parse the metadata content
			String metadataURL = "https://knb.ecoinformatics.org/knb/d1/mn/v2/object/" + id;
			InputStream input = new URL(metadataURL).openStream();
			XMLDialect xml = new XMLDialect(input);
			
			// run a check in the recommendation
			for (Check check: recommendation.getCheck()) {
				Result result = xml.runCheck(check);
				log.debug("Check result: " + JsonMarshaller.toJson(result));
				assertEquals(check.getExpected(), result.getValue());
				break;
			}
			
		} catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		}
	}

	@Test
	public void testRunRecommendationForId() {
		MDQEngine mdqe = new MDQEngine();
		Run run = null;
		try {
			// retrieve the metadata content
			String cnURL = Settings.getConfiguration().getString("D1Client.CN_URL");
			log.error("CN URL: " + cnURL);
			String metadataURL = "https://cn.dataone.org/cn/v2/object/" + id;
			InputStream input = new URL(metadataURL).openStream();
			// run the recommendation on it
			run = mdqe.runRecommendation(recommendation, input);
			run.setObjectIdentifier(id);
			log.debug("Run results JSON: " + JsonMarshaller.toJson(run));
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			fail(e.getMessage());
		}

	}
	
	@Test
	public void testRunRecommendationForPackage() {
		MDQEngine mdqe = new MDQEngine();
		Run run = null;
		try {
			// use the ORE id
			String packageId = "resourceMap_tao.1.1";
			String packageURL = "https://cn.dataone.org/cn/v2/object/" + packageId;
			InputStream input = new URL(packageURL).openStream();
			// run the recommendation on it
			run = mdqe.runRecommendation(recommendation, input);
			run.setObjectIdentifier(packageId);
			log.debug("Run results XML: " + XmlMarshaller.toXml(run));
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			fail(e.getMessage());
		}

	}
}