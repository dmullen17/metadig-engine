package edu.ucsb.nceas.mdqengine.processor;

import edu.ucsb.nceas.mdqengine.model.Output;
import edu.ucsb.nceas.mdqengine.model.Result;
import edu.ucsb.nceas.mdqengine.model.Status;
import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.dataone.client.v2.itk.D1Client;
import org.dataone.service.types.v1.Person;
import org.dataone.service.types.v1.Subject;
import org.dataone.service.types.v1.SubjectInfo;
import org.dataone.service.types.v2.SystemMetadata;
import org.dataone.service.util.TypeMarshaller;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Looks up group membership of given subject
 * @author leinfelder
 *
 */
public class GroupLookupCheck implements Callable<Result> {
	
	// the XML serialization of SystemMetadata
	private String systemMetadata;
	
	public Log log = LogFactory.getLog(this.getClass());
	
	@Override
	public Result call() {
		Result result = new Result();
		Subject subject = null;

		if (this.systemMetadata != null) {
			result.setStatus(Status.SUCCESS);


			
			try {
				SystemMetadata sm = TypeMarshaller.unmarshalTypeFromStream(SystemMetadata.class, IOUtils.toInputStream(systemMetadata, "UTF-8"));
				List<Output> groups = new ArrayList<Output>();
				subject = sm.getRightsHolder();
				log.debug("Looking up SubjectInfo for: " + subject.getValue());
				SubjectInfo subjectInfo = D1Client.getCN().getSubjectInfo(null, subject);
				if (subjectInfo != null && subjectInfo.getPersonList() != null && subjectInfo.getPersonList().size() > 0) {
					Person person = subjectInfo.getPerson(0);
					// get if we are looking up a person or a group
					if (person.getSubject().equals(subject)) {
						if (person.getIsMemberOfList() != null && person.getIsMemberOfList().size() > 0) {
							for (Subject group: person.getIsMemberOfList()) {
								String groupSubject = group.getValue();
								log.debug("Found group: " + groupSubject);
								groups.add(new Output(groupSubject));
							}
							result.setOutput(groups);
						}
					} else {
						// it must be a group already
						result.setOutput(new Output(subject.getValue()));
					}
				}
			} catch (Exception e) {
				result.setStatus(Status.ERROR);
				// If an error occurs, set the output to blank, as this is the value that will be indexed - we don't want
				// to index the error. This message is printed even if it's just the case that the user doesn't belong
				// to a group.
				result.setOutput(new Output(""));
				log.error("Could not look up SubjectInfo", e);
			}
		} else {
			result.setStatus(Status.FAILURE);
			result.setOutput(new Output(""));
			//result.setOutput(new Output("No group info found for rightsholder: " + subject));
			log.warn("No SystemMetadata.rightsHolder given, cannot look up group membership");
		}
		
		return result;
		
	}

	public String getSystemMetadata() {
		return systemMetadata;
	}

	public void setSystemMetadata(String sm) {
		this.systemMetadata = sm;
	}
	
}
