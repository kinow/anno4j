package com.github.anno4j;

import com.github.anno4j.model.impl.ResourceObject;
import com.github.anno4j.model.namespaces.OWL;
import com.github.anno4j.model.namespaces.RDFS;
import com.github.anno4j.querying.evaluation.LDPathEvaluatorConfiguration;
import org.openrdf.model.Resource;
import org.openrdf.model.URI;
import org.openrdf.query.MalformedQueryException;
import org.openrdf.query.QueryEvaluationException;
import org.openrdf.query.QueryLanguage;
import org.openrdf.repository.RepositoryException;
import org.openrdf.repository.object.ObjectQuery;
import org.openrdf.repository.object.ObjectRepository;
import org.openrdf.result.Result;

import java.math.BigInteger;
import java.util.*;

/**
 * A transaction that supports the atomicity property and provides a validation of the datebases schema compliance
 * at commit time.
 * The RDF data in the connected triplestore is validated whether it is compliant to the OWL schema information.
 * The species used is <a href="https://www.w3.org/TR/2004/REC-owl-features-20040210/#s3">OWL Lite</a> with support
 * for other non-negative values for <code>owl:minCardinality</code>, <code>owl:maxCardinality</code>, <code>owl:cardinality</code>.
 */
public class ValidatedTransaction extends Transaction {

    /**
     * Signalizes that the state that results from a {@link ValidatedTransaction}
     * is invalid.
     */
    public static class ValidationFailedException extends RepositoryException {

        /**
         * Initializes the exception without providing a coausing object or property.
         */
        public ValidationFailedException() { }

        /**
         * Initializes the exception with a message.
         * @param message The message.
         */
        public ValidationFailedException(String message) {
            super(message);
        }
    }

    /**
     * Contains prefix definitions for SPARQL queries.
     */
    private static final String QUERY_PREFIX = "PREFIX owl: <" + OWL.NS + "> PREFIX rdfs: <" + RDFS.NS + "> ";

    /**
     * The resource objects that were directly affected by the transaction.
     */
    private Collection<ResourceObject> affectedObjects = new HashSet<>();


    /**
     * {@inheritDoc}
     */
    ValidatedTransaction(ObjectRepository objectRepository, LDPathEvaluatorConfiguration evaluatorConfiguration) throws RepositoryException {
        super(objectRepository, evaluatorConfiguration);
    }

    /**
     * Validates the schema compliance of the state resulting from the transaction.
     * The RDF data in the connected triplestore is validated against OWL schema information that is present in it.
     * If validation failes a {@link ValidationFailedException} is thrown and the transaction is rolled back.
     * @throws ValidationFailedException Thrown if the state the transaction resulted in is not compliant to the schema.
     * Call {@link ValidationFailedException#getMessage()} for information about the reason.
     * @throws RepositoryException Thrown if an errors occurs while querying the connected triplestore.
     */
    @Override
    public void commit() throws RepositoryException {
        Collection<ResourceObject> anchors = getReachableInstances(affectedObjects);

        // Valdiate the graph:
        try {
            validateFunctional(anchors);
            validateInverseFunctional(anchors);
            validateSymmetric(anchors);
            validateTransitive(anchors);

            validateInverseOf(anchors);
            validateSubPropertyOf(anchors);

            validateAllValuesFrom(anchors);
            validateSomeValuesFrom(anchors);
            validateMinCardinality(anchors);
            validateMaxCardinality(anchors);

        } catch (ValidationFailedException e) {
            rollback();
            throw e;
        }

        // No exception thrown, commit the transaction:
        super.commit();
    }

    /**
     * Returns all resources that are reachable via a path of arbitrary length from any of the anchor instances
     * provided.
     * Thus the instances in <code>anchors</code> are also included in the result.
     * @param anchors The resources from where to start scanning.
     * @return The set of all resources that are reachable from the given anchor resources.
     * @throws RepositoryException Thrown if an error occurs regarding the connection to the triplestore.
     */
    private Set<ResourceObject> getReachableInstances(Collection<ResourceObject> anchors) throws RepositoryException {
        // Select all resources that are reachable via an arbirtary path from the anchors:
        String q = QUERY_PREFIX + "SELECT ?o {" +
                buildValuesClause(anchors, "i") +
                "     ?i (<urn:anno4j:foo>|!<urn:anno4j:foo>)* ?o . " +
                "     FILTER( isIRI(?o) )" +
                "}";

        // Evaluate the query:
        Set<ResourceObject> reachable = new HashSet<>(anchors);
        try {
            ObjectQuery query = getConnection().prepareObjectQuery(q);

            for(Object item : query.evaluate().asSet()) {
                if(item instanceof ResourceObject) {
                    reachable.add((ResourceObject) item);
                }
            }

        } catch (MalformedQueryException | QueryEvaluationException e) {
            throw new RepositoryException(e);
        }
        return reachable;
    }

    /**
     * Constructs a SPARQL VALUES clause for successive binding of the given resources to the variable
     * <code>binding</code>.
     * The IRIs of the resources are enclosed in <code>&lt;&gt;</code> brackets.
     * @param values The values to successively bind.
     * @param binding The name of the binding without a leading <code>"?"</code>.
     * @return Returns a SPARQL VALUES clause with the given resources and binding.
     */
    private String buildValuesClause(Collection<ResourceObject> values, String binding) {
        StringBuilder clause = new StringBuilder("VALUES ?")
                                    .append(binding)
                                    .append(" {");

        for (ResourceObject value : values) {
            clause.append(" <")
                  .append(value.getResourceAsString())
                  .append("> ");
        }
        clause.append("}");

        return clause.toString();
    }

    /**
     * Checks that all functional properties that are probably affected by this transaction
     * have unique values.
     * Two values are said to be unique if there is no <code>owl:sameAs</code> path between them.
     * @param anchors The instances from which outgoing properties should be considered.
     * @throws RepositoryException Thrown if an error occurs while querying the connected triplestore.
     * @throws ValidationFailedException Thrown if the validation fails.
     */
    private void validateFunctional(Collection<ResourceObject> anchors) throws RepositoryException, ValidationFailedException {
        // Query for distinct values that are not in a owl:sameAs relation:
        String q = QUERY_PREFIX + "SELECT ?prop ?v1 ?v2 ?i {" +
                buildValuesClause(anchors, "i") +
                "?i ?prop ?v1 . " +
                "?i ?prop ?v2 . " +
                "?prop a owl:FunctionalProperty . " +
                "FILTER ( ?v1 != ?v2 ) " +
                "MINUS { " +
                "       { ?v1 owl:sameAs+ ?v2 . } UNION { ?v2 owl:sameAs+ ?v1 . }" +
                "   }" +
                "} LIMIT 1";

        try {
            ObjectQuery query = getConnection().prepareObjectQuery(q);
            Set<?> result = query.evaluate().asSet();

            if(result.size() > 0) {
                Object[] row = (Object[]) result.iterator().next();
                String propertyIri = row[0].toString();
                String objectIri = row[3].toString();

                throw new ValidationFailedException("There are multiple distinct values for functional property " + propertyIri + " (from " + objectIri + ")");
            }

        } catch (MalformedQueryException e) {
            throw new RepositoryException("Query is malformed. Details: "+ e.getMessage());
        } catch (QueryEvaluationException e) {
            throw new RepositoryException("Query could not be evaluated. Details: " + e.getMessage());
        }
    }

    /**
     * Checks whether every <code>owl:InverseFunctionalProperty</code> which maps to a value from any of the given resources
     * in <code>anchors</code> is actually functional.
     * @param anchors A set of resources which properties will be checked.
     * @throws RepositoryException Thrown if an error occurs querying the connected triplestore.
     * @throws ValidationFailedException Thrown if any of the checked properties violates the above condition.
     */
    private void validateInverseFunctional(Collection<ResourceObject> anchors) throws RepositoryException, ValidationFailedException {
        // Query for subjects that are not in a owl:sameAs relation:
        String q = QUERY_PREFIX + "SELECT ?prop ?o ?v ?i { " +
                buildValuesClause(anchors, "i") +
                "?i ?prop ?v . " +
                "?o ?prop ?v . " +
                "?prop a owl:InverseFunctionalProperty . " +
                "FILTER( ?i != ?o ) " +
                "MINUS { " +
                "        { ?i owl:sameAs+ ?o } UNION { ?o owl:sameAs+ ?i }" +
                "   } " +
                "} LIMIT 1";

        try {
            ObjectQuery query = getConnection().prepareObjectQuery(q);
            Set<?> result = query.evaluate().asSet();

            if(result.size() > 0) {
                Object[] row = (Object[]) result.iterator().next();
                String propertyIri = row[0].toString();
                String secondPreImage = row[1].toString();
                String objectIri = row[3].toString();


                throw new ValidationFailedException("Inverse functional property " + propertyIri
                        + " has multiple distinct pre-images (" + objectIri + ", " + secondPreImage
                        + ") for image " + row[2].toString());
            }

        } catch (QueryEvaluationException | MalformedQueryException e) {
            throw new RepositoryException(e);
        }
    }

    /**
     * Checks whether every <code>owl:SymmetricProperty</code> which maps to a value from any of the given resources
     * in <code>anchors</code> is actually symmetric.
     * @param anchors A set of resources which properties will be checked.
     * @throws RepositoryException Thrown if an error occurs querying the connected triplestore.
     * @throws ValidationFailedException Thrown if any of the checked properties violates the above condition.
     */
    private void validateSymmetric(Collection<ResourceObject> anchors) throws RepositoryException, ValidationFailedException {
        // Query for mappings where the inverse side is missing:
        String q = QUERY_PREFIX + "SELECT ?p ?o ?i {" +
                buildValuesClause(anchors, "i") +
                "   ?i ?p ?o ." +
                "   ?p a owl:SymmetricProperty ." +

                "   MINUS {" +
                "      ?o ?p ?i . " +
                "   }" +

                "} LIMIT 1";

        Set<?> result;
        try {
            ObjectQuery query = getConnection().prepareObjectQuery(q);
            result = query.evaluate().asSet();
        } catch (MalformedQueryException | RepositoryException | QueryEvaluationException e) {
            throw new RepositoryException(e);
        }

        if(result.size() > 0) {
            Object[] row = (Object[]) result.iterator().next();
            String propertyIri = row[0].toString();
            String asymmetricTargetIri = row[1].toString();
            String objectIri = row[2].toString();

            throw new ValidationFailedException("Symmetric property " + propertyIri + " maps " + objectIri +
                    " to " + asymmetricTargetIri + ", but does not map inversely.");
        }

    }

    /**
     * Checks whether every <code>owl:TransitiveProperty</code> which maps to a value from any of the given resources
     * in <code>anchors</code> is actually transitive.
     * @param anchors A set of resources which properties will be checked.
     * @throws RepositoryException Thrown if an error occurs querying the connected triplestore.
     * @throws ValidationFailedException Thrown if any of the checked properties violates the above condition.
     */
    private void validateTransitive(Collection<ResourceObject> anchors) throws RepositoryException, ValidationFailedException {
        for (ResourceObject object : anchors) {
            String affectedObjectIri = object.getResourceAsString();

            // Build the query as a union of subqueries for the outgoing transitive properties of the current instance:
            StringBuilder q = new StringBuilder(QUERY_PREFIX)
                    .append("SELECT ?y ?z {");
            try {
                // All properties that are transitive and outgoing from the current object:
                ObjectQuery query = getConnection().prepareObjectQuery(QUERY_PREFIX + "SELECT ?p { " +
                        "  <" + affectedObjectIri + "> ?p ?o . " +
                        "  ?p a owl:TransitiveProperty . " +
                        "}");

                Result result = query.evaluate();

                while (result.hasNext()){
                    String propertyIri = ((ResourceObject) result.next()).getResourceAsString();

                    // Query for a path instance -> ?y -> ?z, without an edge object -> ?z:
                    q.append("{ <" + affectedObjectIri + "> <" + propertyIri + ">+ ?y ." +
                            "   ?y <" + propertyIri + ">+ ?z ." +

                            "   MINUS {" +
                            "      <" + affectedObjectIri + "> <" + propertyIri + "> ?z ." +
                            "   } " +
                            "}");

                    // Append union operator for all but the last subquery:
                    if(result.hasNext()) {
                        q.append(" UNION ");
                    }
                }

            } catch (MalformedQueryException | QueryEvaluationException e) {
                throw new RepositoryException(e);
            }
            q.append("} LIMIT 1");

            Set<?> result;
            try {
                ObjectQuery query = getConnection().prepareObjectQuery(q.toString());
                result = query.evaluate().asSet();

            } catch (MalformedQueryException | RepositoryException | QueryEvaluationException e) {
                throw new RepositoryException(e);
            }

            if(result.size() > 0) {
                Object[] row = (Object[]) result.iterator().next();
                if(row != null && row[0] != null && row[1] != null) {
                    String intermediateIri = row[0].toString();
                    String targetIri = row[1].toString();

                    throw new ValidationFailedException("Transitive property violates transitivity. " +
                            "No edge exists between " + affectedObjectIri + " and " + targetIri + ", but the latter is reachable via " + intermediateIri + ".");
                }
            }
        }
    }

    /**
     * Checks whether every property which has a <code>owl:inverseOf</code> has the inverse values appropriately set for every resoruce
     * in <code>anchors</code>.
     * @param anchors A set of resources which properties will be checked.
     * @throws RepositoryException Thrown if an error occurs querying the connected triplestore.
     * @throws ValidationFailedException Thrown if any of the checked properties violates the above condition.
     */
    private void validateInverseOf(Collection<ResourceObject> anchors) throws RepositoryException, ValidationFailedException {
        String q = QUERY_PREFIX + "SELECT ?p1 ?o ?i {" +
                buildValuesClause(anchors, "i") +
                "   ?i ?p1 ?o . " +
                "   ?p1 owl:inverseOf ?p2 . " +
                "   MINUS {" +
                "      ?o ?p2 ?i ." +
                "   }" +
                "} LIMIT 1";

        Set<?> result;
        try {
            ObjectQuery query = getConnection().prepareObjectQuery(q);
            result = query.evaluate().asSet();

        } catch (MalformedQueryException | RepositoryException | QueryEvaluationException e) {
            throw new RepositoryException(e);
        }

        if(result.size() > 0) {
            Object[] row = (Object[]) result.iterator().next();
            if(row != null && row[0] != null && row[2] != null) {
                String propertyIri = row[0].toString();
                Object value = row[1];
                String objectIri = row[2].toString();


                throw new ValidationFailedException("Missing inverse mapping from " + value + " to " + objectIri + " by " + propertyIri);
            }
        }
    }

    /**
     * Checks for every properties that is in a <code>rdfs:subPropertyOf</code> relation, whether its value set is actually
     * a subset of all superproperties value sets.
     * @param anchors A set of resources which properties will be checked.
     * @throws RepositoryException Thrown if an error occurs querying the connected triplestore.
     * @throws ValidationFailedException Thrown if any of the checked properties violates the above condition.
     */
    private void validateSubPropertyOf(Collection<ResourceObject> anchors) throws RepositoryException, ValidationFailedException {
        String q = QUERY_PREFIX + "SELECT ?p1 ?p2 ?o ?i {" +
                buildValuesClause(anchors, "i") +
                "          " +
                "   ?i ?p1 ?o ." +
                "   ?p1 rdfs:subPropertyOf+ ?p2 ." +
                "   " +
                "   MINUS {" +
                "     ?i ?p2 ?o ." +
                "   }" +
                "  " +
                "} LIMIT 1";

        Set<?> result;
        try {
            ObjectQuery query = getConnection().prepareObjectQuery(q);
            result = query.evaluate().asSet();
        } catch (MalformedQueryException | RepositoryException | QueryEvaluationException e) {
            throw new RepositoryException(e);
        }

        if(result.size() > 0) {
            Object[] row = (Object[]) result.iterator().next();
            String subPropertyIri = row[0].toString();
            String superPropertyIri = row[1].toString();
            Object value = row[2];
            String objectIri = row[3].toString();


            throw new ValidationFailedException("Superproperty " + superPropertyIri + " of " + subPropertyIri + " is missing value " + value + " (resource: " + objectIri + ")");
        }
    }

    /**
     * Checks whether every <code>owl:allValuesFrom</code> restriction imposed on any of the classes of the instances
     * <code>anchors</code> is fulfilled.
     * @param anchors A set of resources which properties will be checked.
     * @throws RepositoryException Thrown if an error occurs querying the connected triplestore.
     * @throws ValidationFailedException Thrown if any of the checked properties violates the above condition.
     */
    private void validateAllValuesFrom(Collection<ResourceObject> anchors) throws RepositoryException, ValidationFailedException {
        String q = QUERY_PREFIX + "SELECT ?p ?o ?c ?i {" +
                buildValuesClause(anchors, "i") +
                "    ?i a ?t ." +

                "    ?t rdfs:subClassOf+ ?r ." +
                "    ?r a owl:Restriction ." +
                "    ?r owl:onProperty ?p ." +
                "    ?r owl:allValuesFrom ?c ." +

                "    ?i ?p ?o ." +

                "    MINUS { " +
                "        { ?o a ?c . } UNION { ?o a ?d . ?d rdfs:subClassOf+ ?c . }" +
                "    }" +
                "} LIMIT 1";

        Set<?> result;
        try {
            ObjectQuery query = getConnection().prepareObjectQuery(q);
            result = query.evaluate().asSet();

        } catch (MalformedQueryException | RepositoryException | QueryEvaluationException e) {
            throw new RepositoryException(e);
        }

        if(result.size() > 0) {
            Object[] row = (Object[]) result.iterator().next();
            String propertyIri = row[0].toString();
            String valueIri = row[1].toString();
            String targetClassIri = row[2].toString();
            String objectIri = row[3].toString();


            throw new ValidationFailedException("Value " + valueIri + " of " + propertyIri +
                    " (from " + objectIri + ") is not of required type " + targetClassIri);
        }
    }

    /**
     * Checks whether every <code>owl:someValuesFrom</code> restriction imposed on any of the classes of the instances
     * <code>anchors</code> is fulfilled.
     * @param anchors A set of resources which properties will be checked.
     * @throws RepositoryException Thrown if an error occurs querying the connected triplestore.
     * @throws ValidationFailedException Thrown if any of the checked properties violates the above condition.
     */
    private void validateSomeValuesFrom(Collection<ResourceObject> anchors) throws RepositoryException, ValidationFailedException {
        String q = QUERY_PREFIX + "SELECT DISTINCT ?p ?c ?i (COUNT(?o) as ?total) (COUNT(?o2) as ?count) {  " +
                buildValuesClause(anchors, "i") +
                "  ?i a ?t ." +

                "  ?t rdfs:subClassOf+ ?r ." +
                "  ?r a owl:Restriction ." +
                "  ?r owl:onProperty ?p . " +
                "  ?r owl:someValuesFrom ?c . " +

                "  {" +
                "     ?i ?p ?o . " +
                "  }" +
                "  UNION {" +
                "      ?i ?p ?o2 ." +
                "      ?o2 a ?c . " +
                "  }" +
                "  UNION {" +
                "      ?i ?p ?o2 ." +
                "      ?o2 rdf:type/(rdfs:subClassOf+) ?c . " +
                "  }" +

                "} GROUP BY ?p ?c ?i";

        Set<?> result;
        try {
            ObjectQuery query = getConnection().prepareObjectQuery(q);
            result = query.evaluate().asSet();
        } catch (MalformedQueryException | RepositoryException | QueryEvaluationException e) {
            throw new RepositoryException(e);
        }

        for (Object item : result) {
            Object[] row = (Object[]) item;
            int totalValueCount = ((BigInteger) row[3]).intValue();
            int fromClassCount = ((BigInteger) row[4]).intValue();

            if (totalValueCount > 0 && fromClassCount == 0) {
                String propertyIri = row[0].toString();
                String targetClassIri = row[1].toString();
                String objectIri = row[2].toString();

                throw new ValidationFailedException("At least one value mapped by " + propertyIri +
                        " (from " + objectIri + ") must be of type " + targetClassIri);
            }
        }
    }

    /**
     * Returns how many values of the individuals <code>subjectURI</code> property <code>propertyURI</code>
     * are of a certain type.
     * @param subjectURI The URI of the subject for which the property values will be checked.
     * @param propertyURI The URI of the property which values will be checked.
     * @param valueTypeURI The URI of the class that will be counted across the values.
     * @return Returns the number of values of type <code>valueTypeURI</code> that <code>subjectURI</code> has
     * for the property <code>propertyURI</code>.
     * @throws RepositoryException Thrown if an error occurs regarding the connected triplestore.
     * @throws MalformedQueryException Thrown if the issued query to the triplestore is malformed, e.g. because any argument is not a valid IRI.
     * @throws QueryEvaluationException Thrown if the issued query could not be evaluated.
     */
    private int getValuesOfType(String subjectURI, String propertyURI, String valueTypeURI) throws RepositoryException, MalformedQueryException, QueryEvaluationException {
        ObjectQuery query = getConnection().prepareObjectQuery(QUERY_PREFIX + "SELECT (COUNT(?o) as ?count) {" +
                                                            "   <" + subjectURI + "> <" + propertyURI + "> ?o . " +
                                                            "   { " +
                                                            "      ?o a ?t ." +
                                                            "      ?t rdfs:subClassOf+ <" + valueTypeURI + "> . " +
                                                            "   }" +
                                                            "   UNION " +
                                                            "   {" +
                                                            "      ?o a <" + valueTypeURI + "> . " +
                                                            "   }" +
                                                            "}");
        return  ((BigInteger) query.evaluate().next()).intValue();
    }

    /**
     * Checks whether every <code>owl:minCardinality</code> restriction imposed on any of the classes of the instances
     * <code>anchors</code> is fulfilled.
     * Also checks the validity of <code>owl:onClass</code> constraints.
     * @param anchors A set of resources which properties will be checked.
     * @throws RepositoryException Thrown if an error occurs querying the connected triplestore.
     * @throws ValidationFailedException Thrown if any of the checked properties violates the above condition.
     */
    private void validateMinCardinality(Collection<ResourceObject> anchors) throws RepositoryException, ValidationFailedException {
        // Get the minimum and actual cardinality of properties. Also get the qualified class if there is any specified:
        String q = QUERY_PREFIX + "SELECT ?p ?mc (COUNT(DISTINCT ?o) as ?c) ?oc ?i {" +
                buildValuesClause(anchors, "i") +
                "  ?i a ?t ." +
                "  ?t rdfs:subClassOf+ ?r ." +
                "  ?r a owl:Restriction ." +
                "  ?r owl:onProperty ?p ." +
                "  ?r owl:minCardinality ?mc ." +

                "  ?i ?p ?o ." +

                "  MINUS {" +
                "     ?i ?p ?o2 ." +
                "     ?o2 owl:sameAs+ ?o ." +
                "  }" +
                "  OPTIONAL {" +
                "     ?r owl:onClass ?oc ." +
                "  }" +
                "} GROUP BY ?p ?mc ?oc ?i";

        Set<?> result;
        try {
            ObjectQuery query = getConnection().prepareObjectQuery(q);
            result = query.evaluate().asSet();


        } catch (MalformedQueryException | RepositoryException | QueryEvaluationException e) {
            throw new RepositoryException(e);
        }

        for (Object current : result) {
            // Get the bindings:
            Object[] row = (Object[]) current;
            if(row != null && row[0] != null && row[1] != null && row[4] != null) {
                String propertyIri = row[0].toString();
                String objectIri = row[4].toString();

                int minCardinality = (int) row[1];
                int cardinality = ((BigInteger) row[2]).intValue();

                // We can directly decide whether there are too few values of any type:
                if(cardinality < minCardinality) {
                    throw new ValidationFailedException("Property " + propertyIri + " of " + objectIri
                            + " has only " + cardinality + " values, but minimum cardinality is " + minCardinality);
                }

                // If there is a minimum count for values of a certain type specified, also check that:
                if(row[3] != null) {
                    String onClassIri = row[3].toString();
                    int onClassValueCount;
                    try {
                        onClassValueCount = getValuesOfType(objectIri, propertyIri, onClassIri);
                    } catch (QueryEvaluationException | MalformedQueryException e) {
                        throw new RepositoryException(e);
                    }

                    // Throw exception if there are too few values of the given type:
                    if(onClassValueCount < minCardinality) {
                        throw new ValidationFailedException("Property " + propertyIri + " of " + objectIri +
                                " requires at least " + minCardinality + " values of type " + onClassIri + " but only "
                                + onClassValueCount + " of " + cardinality + " have this type.");
                    }
                }
            }
        }
    }


    /**
     * Checks whether every <code>owl:maxCardinality</code> restriction imposed on any of the classes of the instances
     * <code>anchors</code> is fulfilled.
     * Also checks the validity of <code>owl:onClass</code> constraints.
     * @param anchors A set of resources which properties will be checked.
     * @throws RepositoryException Thrown if an error occurs querying the connected triplestore.
     * @throws ValidationFailedException Thrown if any of the checked properties violates the above condition.
     */
    private void validateMaxCardinality(Collection<ResourceObject> anchors) throws RepositoryException, ValidationFailedException {
        // Get the maximum and actual cardinality of properties. Also get the qualified class if there is any specified:
        String q = QUERY_PREFIX + "SELECT ?p ?mc (COUNT(DISTINCT ?o) as ?c) ?oc ?i {" +
                buildValuesClause(anchors, "i") +
                "  ?i a ?t ." +
                "  ?t rdfs:subClassOf+ ?r ." +
                "  ?r a owl:Restriction ." +
                "  ?r owl:onProperty ?p ." +
                "  ?r owl:maxCardinality ?mc ." +

                "  ?i ?p ?o ." +

                "  MINUS {" +
                "     ?i ?p ?o2 ." +
                "     ?o2 owl:sameAs+ ?o ." +
                "  }" +
                "  OPTIONAL {" +
                "     ?r owl:onClass ?oc ." +
                "  }" +
                "} GROUP BY ?p ?mc ?oc ?i";

        Set<?> result;
        try {
            ObjectQuery query = getConnection().prepareObjectQuery(q);
            result = query.evaluate().asSet();

        } catch (MalformedQueryException | RepositoryException | QueryEvaluationException e) {
            throw new RepositoryException(e);
        }

        for(Object current : result) {
            // Get the bindings:
            Object[] row = (Object[]) current;

            if(row != null && row[0] != null && row[1] != null && row[4] != null) {
                String propertyIri = row[0].toString();
                String objectIri = row[4].toString();

                int maxCardinality = (int) row[1];
                int cardinality = ((BigInteger) row[2]).intValue();

                // We can directly decide from the above query result whether there are too many values of any type:
                if(cardinality > maxCardinality && row[3] == null) {
                    throw new ValidationFailedException("Property " + propertyIri + " of " + objectIri
                            + " has only " + cardinality + " values, but maximum cardinality is " + maxCardinality);
                }

                // If there is a maximum count for values of a certain type specified, also check that:
                if(row[3] != null) {
                    String onClassIri = row[3].toString();
                    int onClassValueCount;
                    try {
                        onClassValueCount = getValuesOfType(objectIri, propertyIri, onClassIri);
                    } catch (QueryEvaluationException | MalformedQueryException e) {
                        throw new RepositoryException(e);
                    }


                    if(maxCardinality < onClassValueCount) {
                        throw new ValidationFailedException("Property " + propertyIri + " of " + objectIri +
                                " must have at most " + maxCardinality + " values of type " + onClassIri + " but "
                                + onClassValueCount + " of have this type.");
                    }
                }
            }
        }
    }

    @Override
    public void persist(ResourceObject resource) throws RepositoryException {
        super.persist(resource);
        affectedObjects.add(resource);
    }

    @Override
    public <T extends ResourceObject> T findByID(Class<T> type, String id) throws RepositoryException {
        T result = super.findByID(type, id);

        if(result != null) {
            affectedObjects.add(result);
        }

        return result;
    }

    @Override
    public <T extends ResourceObject> T findByID(Class<T> type, URI id) throws RepositoryException {
        return findByID(type, id.toString());
    }

    @Override
    public <T extends ResourceObject> List<T> findAll(Class<T> type) throws RepositoryException {
        List<T> result = super.findAll(type);

        if(result != null) {
            affectedObjects.addAll(result);
        }

        return result;
    }

    @Override
    public <T> T createObject(Class<T> clazz) throws RepositoryException, IllegalAccessException, InstantiationException {
        return createObject(clazz, null);
    }

    @Override
    public <T> T createObject(Class<T> clazz, Resource id) throws RepositoryException, IllegalAccessException, InstantiationException {
        T object = super.createObject(clazz, id);
        affectedObjects.add((ResourceObject) object);
        return object;
    }
}