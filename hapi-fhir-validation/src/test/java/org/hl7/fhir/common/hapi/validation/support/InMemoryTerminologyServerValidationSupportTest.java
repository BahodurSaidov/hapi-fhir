package org.hl7.fhir.common.hapi.validation.support;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.ConceptValidationOptions;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.context.support.ValidationSupportContext;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.CodeSystem;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.ValueSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryTerminologyServerValidationSupportTest {

	private InMemoryTerminologyServerValidationSupport mySvc;
	private FhirContext myCtx = FhirContext.forR4();
	private DefaultProfileValidationSupport myDefaultSupport;
	private ValidationSupportChain myChain;
	private PrePopulatedValidationSupport myPrePopulated;

	@BeforeEach
	public void before() {
		mySvc = new InMemoryTerminologyServerValidationSupport(myCtx);
		myDefaultSupport = new DefaultProfileValidationSupport(myCtx);
		myPrePopulated = new PrePopulatedValidationSupport(myCtx);
		myChain = new ValidationSupportChain(mySvc, myPrePopulated, myDefaultSupport);

		// Force load
		myDefaultSupport.fetchCodeSystem("http://foo");
	}

	@Test
	public void testValidateCodeInUnknownCodeSystemWithEnumeratedValueSet() {
		ValueSet vs = new ValueSet();
		vs.setUrl("http://vs");
		vs
			.getCompose()
			.addInclude()
			.setSystem("http://cs")
			.addConcept(new ValueSet.ConceptReferenceComponent(new CodeType("code1")))
			.addConcept(new ValueSet.ConceptReferenceComponent(new CodeType("code2")));
		myPrePopulated.addValueSet(vs);

		ValidationSupportContext valCtx = new ValidationSupportContext(myChain);
		ConceptValidationOptions options = new ConceptValidationOptions();

		IValidationSupport.CodeValidationResult outcome = myChain.validateCodeInValueSet(valCtx, options, "http://cs", "code1", null, vs);
		assertTrue(outcome.isOk());

		outcome = myChain.validateCodeInValueSet(valCtx, options, "http://cs", "code99", null, vs);
		assertNull(outcome);

	}

	@Test
	public void testValidateCodeDstu2() {
		FhirContext ctxDstu2 = FhirContext.forDstu2Hl7Org();
		PrePopulatedValidationSupportDstu2 dstu2PrePopulated = new PrePopulatedValidationSupportDstu2(ctxDstu2);
		mySvc = new InMemoryTerminologyServerValidationSupport(ctxDstu2);
		myDefaultSupport = new DefaultProfileValidationSupport(ctxDstu2);
		myChain = new ValidationSupportChain(mySvc, dstu2PrePopulated, myDefaultSupport);

		org.hl7.fhir.dstu2.model.ValueSet vs = new org.hl7.fhir.dstu2.model.ValueSet();
		vs.setUrl("http://vs");
		vs
			.getCompose()
			.addInclude()
			.setSystem("http://cs")
			.addConcept(new org.hl7.fhir.dstu2.model.ValueSet.ConceptReferenceComponent(new org.hl7.fhir.dstu2.model.CodeType("code1")))
			.addConcept(new org.hl7.fhir.dstu2.model.ValueSet.ConceptReferenceComponent(new org.hl7.fhir.dstu2.model.CodeType("code2")));
		vs.getCodeSystem()
			.addConcept(new org.hl7.fhir.dstu2.model.ValueSet.ConceptDefinitionComponent(new org.hl7.fhir.dstu2.model.CodeType("code1")))
			.addConcept(new org.hl7.fhir.dstu2.model.ValueSet.ConceptDefinitionComponent(new org.hl7.fhir.dstu2.model.CodeType("code2")));

		dstu2PrePopulated.addValueSet(vs, "http://vs", "http://cs");

		ValidationSupportContext valCtx = new ValidationSupportContext(myChain);
		ConceptValidationOptions options = new ConceptValidationOptions();

		IValidationSupport.CodeValidationResult outcome = mySvc.validateCode(valCtx, options, "http://cs", "code1", null, "http://vs");
		assertTrue(outcome.isOk());

	}

	@Test
	public void testValidateCodeWithVersionedCodeSystemUrl_Matching() {
		CodeSystem cs = new CodeSystem();
		cs.setId("snomed-ct-ca-imm");
		cs.setStatus(Enumerations.PublicationStatus.ACTIVE);
		cs.setContent(CodeSystem.CodeSystemContentMode.FRAGMENT);
		cs.setUrl("http://snomed.info/sct");
		cs.setVersion("http://snomed.info/sct/20611000087101/version/20210331");
		cs.addConcept().setCode("28571000087109").setDisplay("MODERNA COVID-19 mRNA-1273");
		myPrePopulated.addCodeSystem(cs);

		ValueSet vs = new ValueSet();
		vs.setId("vaccinecode");
		vs.setUrl("http://ehealthontario.ca/fhir/ValueSet/vaccinecode");
		vs.setVersion("0.1.17");
		vs.setStatus(Enumerations.PublicationStatus.ACTIVE);
		ValueSet.ConceptSetComponent vsInclude = vs.getCompose().addInclude();
		vsInclude.setSystem("http://snomed.info/sct");
		vsInclude.setVersion("http://snomed.info/sct/20611000087101/version/20210331");
		vsInclude.addConcept().setCode("28571000087109").setDisplay("MODERNA COVID-19 mRNA-1273");
		myPrePopulated.addValueSet(vs);

		ValidationSupportContext valCtx = new ValidationSupportContext(myChain);
		ConceptValidationOptions options = new ConceptValidationOptions();

		String codeSystemUrl;
		String valueSetUrl;
		String code;
		IValidationSupport.CodeValidationResult outcome;

		// Good code
		codeSystemUrl = "http://snomed.info/sct";
		valueSetUrl = "http://ehealthontario.ca/fhir/ValueSet/vaccinecode";
		code = "28571000087109";
		outcome = mySvc.validateCode(valCtx, options, codeSystemUrl, code, null, valueSetUrl);
		assertTrue(outcome.isOk());

		// Bad code
		codeSystemUrl = "http://snomed.info/sct";
		valueSetUrl = "http://ehealthontario.ca/fhir/ValueSet/vaccinecode";
		code = "123";
		outcome = mySvc.validateCode(valCtx, options, codeSystemUrl, code, null, valueSetUrl);
		assertFalse(outcome.isOk());
	}

	@Test
	public void testValidateCodeWithVersionedCodeSystemUrl_NotMatching() {
		CodeSystem cs = new CodeSystem();
		cs.setId("snomed-ct-ca-imm");
		cs.setStatus(Enumerations.PublicationStatus.ACTIVE);
		cs.setContent(CodeSystem.CodeSystemContentMode.FRAGMENT);
		cs.setUrl("http://snomed.info/sct");
		cs.setVersion("http://snomed.info/sct/20611000087101/version/20210331");
		cs.addConcept().setCode("28571000087109").setDisplay("MODERNA COVID-19 mRNA-1273");
		myPrePopulated.addCodeSystem(cs);

		ValueSet vs = new ValueSet();
		vs.setId("vaccinecode");
		vs.setUrl("http://ehealthontario.ca/fhir/ValueSet/vaccinecode");
		vs.setVersion("0.1.17");
		vs.setStatus(Enumerations.PublicationStatus.ACTIVE);
		ValueSet.ConceptSetComponent vsInclude = vs.getCompose().addInclude();
		vsInclude.setSystem("http://snomed.info/sct");
		vsInclude.setVersion("0.17"); // different version
		vsInclude.addConcept().setCode("28571000087109").setDisplay("MODERNA COVID-19 mRNA-1273");
		myPrePopulated.addValueSet(vs);

		ValidationSupportContext valCtx = new ValidationSupportContext(myChain);
		ConceptValidationOptions options = new ConceptValidationOptions();

		String codeSystemUrl;
		String valueSetUrl;
		String code;
		IValidationSupport.CodeValidationResult outcome;

		codeSystemUrl = "http://snomed.info/sct";
		valueSetUrl = "http://ehealthontario.ca/fhir/ValueSet/vaccinecode";
		code = "28571000087109";
		outcome = mySvc.validateCode(valCtx, options, codeSystemUrl, code, null, valueSetUrl);
		assertNull(outcome);
	}

	private static class PrePopulatedValidationSupportDstu2 extends PrePopulatedValidationSupport {
		private final Map<String, IBaseResource> myDstu2ValueSets;

		PrePopulatedValidationSupportDstu2(FhirContext theFhirContext) {
			super(theFhirContext);
			myDstu2ValueSets = new HashMap<>();
		}

		public void addValueSet(org.hl7.fhir.dstu2.model.ValueSet theValueSet, String valueSetUrl, String codeSystemUrl) {
			myDstu2ValueSets.put(valueSetUrl, theValueSet);
			myDstu2ValueSets.put(codeSystemUrl, theValueSet);
		}

		@Override
		public IBaseResource fetchValueSet(String theUri) {
			return myDstu2ValueSets.get(theUri);
		}

		@Override
		public IBaseResource fetchCodeSystem(String theSystem) {
			return myDstu2ValueSets.get(theSystem);
		}

	}

}
