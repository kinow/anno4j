# Anno4j

> This library provides programmatic access the [W3C Web Annotation Data Model](http://www.w3.org/TR/annotation-model/) (formerly known as the [W3C Open Annotation Data Model](http://www.openannotation.org/spec/core/)) to allow annotations to be written from and to local or remote SPARQL endpoints. An easy-to-use and extensible Java API allows creation and querying of annotations even for non-experts.  

## Build Status
master branch: [![Build Status](https://travis-ci.org/anno4j/anno4j.svg?branch=master)](https://travis-ci.org/anno4j/anno4j) develop branch: [![Build Status](https://travis-ci.org/anno4j/anno4j.svg?branch=develop)](https://travis-ci.org/anno4j/anno4j)

## Features:

- Extensible creation of Web/Open Annotations based on Java Annotations syntax
- Built-in and predefined implementations for nearly all RDF classes conform to the W3C Web Annotation Data Model
- Created (and annotated) Java POJOs are transformed to RDF and automatically transmitted to local/remote SPARQL 1.1 endpoints using the SPARQL Update functionality
- Querying of annotations with path-based criteria
    - [x] Basic comparisons like "equal", "greater", and "lower"
    - [x] String comparisons: "equal", "contains", "starts with", and "ends with"
    - [x] Union of different paths
    - [x] Type condition
    - [x] Custom filters
    
## Outline
- [Introduction](#introduction)
- [Getting Started](#getting-started)
    - [Installation](#Installation)
    - [Configuration](#configuration)
    - [Create and Save Annotations](#create-and-save-annotations)
    - [Query for Annotations](#query-for-annotations)
    - [Transactions](#transactions)
    - [Graph Context](#graph-context)
    - [Input and Output](#input-and-output)
- [Example](#example)
- [Restrictions](#restrictions)
- [Contributors](#contributors)
- [License](#license)

## Introduction
Anno4j is a Java RDF library to easily cope with annotations conform to the Web Annotation Data Model / Open Annotation Data Model. The library provides an extensible way to create annotations while supporting bundles of pre-defined RDF class interfaces. Annotations are automatically persisted on (locally or remotely) connected [SPARQL (SPARQL Protocol and RDF Query Language)](http://www.w3.org/TR/sparql11-overview/) endpoints without having to issue any kind of SPARQL query. Besides the creation of annotations, Anno4j also provides an easy-to-use query API based on the path-based query language [LDPath](http://marmotta.apache.org/ldpath/).

The Web Annotation Data Model / Open Annotation Data Model specification describes a structured model and format to enable (web) annotations to be shared and reused across different hardware and software platforms. The model is based on [RDF (Resource Description Framework)](http://www.w3.org/TR/rdf11-primer/), the de-facto-standard model for publishing and interlinking data over the Web.

## Getting Started

### Installation

Add the maven dependency with the respective version (current major version is 2.3.0, Anno4j is in the oss.sonatype.org repository):

```xml
<dependency>
	<groupId>com.github.anno4j</groupId>
	<artifactId>anno4j-core</artifactId>
	<version>...</version>
</dependency>
```     

### Configuration

Unlike the first versions of Anno4j, the current state does not implement the singleton pattern anymore. Without the singleton pattern, 
it is now possible to use multiple Anno4j instances and consecutively multiple triple-stores. To create an Anno4j instance,
simply use the Anno4j class constructor:

```java
    Anno4j anno4j = new Anno4j();
```

The default configuration of Anno4j is set to a local in-memory SPARQL triple-store. Anno4j is based on [Sesame](http://rdf4j.org/). To connect to your personal
local or remote SPARQL endpoint, just create a corresponding repository object (see [here](http://rdf4j.org/sesame/2.7/docs/users.docbook?view#section-repository-api) for the Sesame documentation of repositories) 
and use the corresponding setter of your Anno4j instance.

```java
    anno4j.setRepository(new SPARQLRepository("http://www.mydomain.com/sparql"));
```       

For RDF creation, Anno4j needs a central instance to generate unique identifiers. The ID generator needs to implement 
the *org.openrdf.IDGenerator* interface. To activate your ID generator, create a respective object and set it in the Anno4j instance.

```java
    anno4j.setIdGenerator(new MyIDGenerator());
```       


### Create and Save Annotations

Anno4j uses [AliBaba](https://bitbucket.org/openrdf/alibaba/) to provide an easy way to create RDF by simply annotating Java interfaces with the *@IRI* Java annotation. To indicate for example that a given 
interface is an *Annotation* you need to add "@Iri(OADM.ANNOTAION)" directly above the class declaration. 

```java
	@Iri(OADM.ANNOTATION)
    public interface Annotation extends ResourceObject { ... }
```

OADM.ANNOTATION is a predefined constant for the IRI: *http://www.w3.org/ns/oa#Annotation* (Other predefined namespaces are 
declared in the *com.github.anno4j.model.namespaces* package). When a respective instance is then persisted via Anno4j (merely the creation of the object is enough, see below), the following triple describing the type relationship is created:

    <http://example.org/exampleAnnotation> rdf:type <http://www.w3.org/ns/oa#Annotation>
 
To specify other triples and corresponding relationships, the *@Iri* annotation has to be
added directly above the respective getter/setter pair of the interface. An example for the body of an OADM annotation, utilising the IRI *@Iri(OADM.HAS_BODY)*, would look like this:

```java
    @Iri(OADM.HAS_BODY)
    Body getBody() {...};
    
    @Iri(OADM.HAS_BODY)
    void setBody(Body body) {...};
```

This would result in the following RDF triple:

    <http://example.org/exampleAnnotation> <http://www.w3.org/ns/oa#hasBody> <http://example.org/exampleBody>

Once the interface is set up with its type IRI as well as annotated getters and setters, instances of the respective interface can be created by using the *.createObject()* method. Anno4j automatically persists them at its respectively assigned triple-store. The creation of an annotation that we just defined above would look like this:

```java
	// Create Anno4j instance
	Anno4j anno4j = new Anno4j();

    // Simple Annotation and Body object
    Annotation annotation = anno4j.createObject(Annotation.class);
    
    // Modify the Annotation
    annotation.setBody( ... );
```

This would lead to the persistence of the annotation object and all of its annotated attributes to the preset repository.

### Query for Annotations

Anno4j also allows to query triple-stores without writing own SPARQL queries. Therefore it provides hibernate like criteria
queries to query against a particular class. Furthermore Anno4j supports a so-called fluent interface, that allows method chaining
and therefore helps the user to create their respective query more easily, as they are better to read and a query can be seperated into smaller bits and pieces for better convenience.

The following code shows how to get an instance of the *QueryService* (related to a given Anno4j object), which is responsible for all provided querying mechanisms. 
It also gives insight on how to use the method chaining ability of the fluent interface. 

However, before querying can happen, it is important to make sure that every namespace is known to the specific QueryService instance, if you intend to use abbreviations. A custom namespace can be registered with the following command, also displaying of how to create a QueryService ontop of a given Anno4j instance:

```java
	// Create QueryService object
	QueryService queryService = anno4j.createQueryService();
	
	// Register custom namespace with abbreviation ex
	queryService.addPrefix("ex", "http://www.example.com/schema#");
```

Using a custom namespace allows to apply its abbreviation when writing queries, so for example instead of writing *http://www.example.com/schema#SomeClass* you can write *ex:SomeClass*. Some namespaces are predefined and thereby always available without being specified. These are:

    oa:			<http://www.w3.org/ns/oa#>
    cnt:		<http://www.w3.org/2011/content#>
    dc:			<http://purl.org/dc/elements/1.1/>
    dcterms:	<http://purl.org/dc/terms/>
    dctypes:	<http://purl.org/dc/dcmitype>
    foaf:		<http://xmlns.com/foaf/0.1/>
    prov:		<http://www.w3.org/ns/prov/>
    rdf:		<http://www.w3.org/1999/02/22-rdf-syntax-ns#>
    rdfs:		<http://www.w3.org/2000/01/rdf-schema#>
    skos:		<http://www.w3.org/2004/02/skos/core#>

Once all namespaces are added, the next step consists of adding criteria to the QueryService, which will ultimately define the query that can be executed. This is done by using the *addCriteria(String ldPath)* method of the QueryService. The criteria in the *ldPath* parameter therefore must be conform to the [LDPath](http://marmotta.apache.org/ldpath/) specification. LDPath is a simple path-based query language similar to [XPath](https://www.w3.org/TR/xpath/) or [SPARQL Property Paths](https://www.w3.org/TR/sparql11-query/#propertypaths), that is particularly well-suited for querying and retrieving resources from a given RDF graph. LDPath offers a broad variety of tools to form comprehensive queries, extensive examples of how to use it in conjunction with Anno4j can be seen in the test suite found in the *com.github.anno4j.querying* package. A path criteria, testing if an Annotation has a Selector that is of the type *oa:FragmentSelector*, could look as follows:

    oa:hasTarget / oa:hasSelector[is-a oa:FragmentSelector]
    
Next to a solely path-based criteria, comparison criteria are also supported by using the *addCriteria(String ldPath, String value, Comparison comparison)* method. It allows to define the path to an RDF field that is then compared against the supported *value*/*comparison* constraint. The *value* is a basic datatype like numbers or a String, which will be compared against the value of the respective RDF field. Anno4j supports various comparison operators, such as:

- Equal (Comparison.EQ)
- Greater than (Comparison.GT)
- Greater than or equal (Comparison.GTE)
- Lower than (Comparison.LT)
- Lower than or equal (Comparison.LTE)
- String: contains (Comparison.CONTAINS)
- String: starts with (Comparison.STARTS_WITH)
- String: ends with (Comparison.ENDS_WITH)

If a comparison method is not provided, the query service will use Comparison.EQ by default. The following example code shows 
how the addCriteria function could be invoked to query for a specific value (starting from an Annotation, over the relationships *oa:hasBody* and *ex:value*) of a given body:

```java
    queryService.addCriteria("oa:hasBody/ex:value", "Example Value", Comparison.EQ);
```

After adding single or multiple criteria to the QueryService, its query can be executed. Therefore, the QueryService will automatically create a SPARQL query
according to the users' namespaces and criteria that have been defined and issues this query to retrieve the respective data from the defined triple store. To achieve this, the *.execute()* or *.execute(Class<T> type)* method has
to be invoked. The *type* parameter defines the RDF class that is used as starting point and return type for the given query. Its value is supposed to match a given Anno4j Java class. For example, one could add a specific body type (e.g. *EX.EXAMPLE_BODY*) which is also applied to the respective class), in order to start the query from there. If no *type* is supported, the default starting point is the Annotation (*OADM.ANNOTATION*).

A complete QueryService example can be seen in the following code snippet: 

```java
	QueryService queryService = anno4j.createQueryService();
   List<ExampleBody> result = queryService
        .addPrefix("ex", "http://www.example.com/schema#")
        .addCriteria("^oa:hasBody/dcterms:modified, "2016-01-28T12:00:00Z", Comparison.EQ)
        .addCriteria("ex:confidence", 0.5, Comparison.GT)
        .execute(ExampleBody.class);
```

After registering an own namespace *ex*, the query would select and return all those body objects, that ...

- ... are of the type *ExampleBody*,
- ... are associated with an Annotation node (backwards edge indcated by *^oa:hasBody*), that was modified at the exact date of "2016-01-28T12:00:00Z", and
- ... have a confidence value assigned (relationship *ex:confidence*), that is higher 0.5.

### Transactions

Anno4j's persistence and querying can be enhanced with a transactional behaviour. This means that the library runs their commands in atomic units of work. Consequenctly, one array of commands is either fully executed (*commited*) or not at all (*rolled back*). This enables the database to be consistent at every possible time.

Many of Anno4j's basic methods are set to auto-commit, but it also supports the *createTransaction()* method to create a transaction of your own, which needs to be started, and then committed or rolled back manually in order to have effect.

An exemplary code snippet can be seen here (note that the begin and commit of the transaction are essential!):

```java
	Anno4j anno4j = new Anno4j();
	
	Transaction transaction = anno4j.createTransaction();
	transaction.begin();
	
	// Create and query different things over the transaction
	transaction.createObject(...);
	QueryService qs = transaction.createQueryService();
	
	transaction.commit();
```

### Graph Context

Anno4j does support the RDF graph functionality which uses subgraphs and turns triples into quadruples to allow further structuring of ones data in a more fine-grained way. This can be done either at the Anno4j object or at the Transaction object directly. Two out of the four *createObject(...)* methods of the Anno4j class support an URI parameter called *context* which is uzilised for the subgraph. The Transaction class supports a *setAllContexts(URI context)* method to set a subgraph for the whole transaction.

```java
	URI uri = new URIImpl("http://www.somePage.com/");

	Annotation annotation = anno4j.createObject(Annotation.class, uri);
	
	Transaction transaction = anno4j.createTransaction();
	transaction.setAllContexts(uri);
```

### Input and Output

To improve the usability of the library, a small extension to the "ORM" (Object-RDF-Mapping) has been implemented. Users can parse their RDF triples formulated in various RDF serialisations to create the respective Java objects, as well as write their Java objects to serialised RDF triples. Among the available serialisations are rdf/xml, ntriples, turtle, n3, jsonld, rdf/json, etc.

In order to read a given RDF annotation (available as Java String), an *ObjectParser* object is needed. Its *.parse(String annotation, String uri, RDFFormat format)* requires the *annotation* as String, a *uri* for namespacing, and the supported *format* (The Java Class *RDFFormat* holds constant for all supported serialisations). It will then return a Java List of parsed Annotations. Important to note is, that all RDF nodes that are to be parsed need to be supported as respective Anno4j interfaces. The ObjectParser holds all parsed annotations in a local temporary memory store. In order to persist them to your respective database, they have to be read from the parser and be persisted "by hand" then. The following shows an example of how to read a turtle annotation.

```java   
String TURTLE = "@prefix oa: <http://www.w3.org/ns/oa#> ." +
            "@prefix ex: <http://www.example.com/ns#> ." +
            "@prefix dctypes: <http://purl.org/dc/dcmitype/> ." +
            "@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> ." +

            "ex:anno1 a oa:Annotation ;" +
            "   oa:hasBody ex:body1 ;" +
            "   oa:hasTarget ex:target1 .";
            
URL url = new URL("http://example.com/");

ObjectParser objectParser = new ObjectParser();
List<Annotation> annotations = objectParser.parse(TURTLE, url, RDFFormat.TURTLE);

objectParser.shutdown();
```

To write a given Anno4j Java object to respective RDF serialisations, the *ResourceObject* interface (which every Anno4j object descends from) supports a *.getTriples(RDFFormat format)* method, which returns the representation of the object as a Java String in the supported *format*. The following shows an example that writes a given annotation as turtle triples.

```java   
Annotation annotation = anno4j.createObject(Annotation.class);

// Add something to the Annotation

String annotationAsTurtle = annotation.getTriples(RDFFormat.TURTLE);
```

## Example

The following will guide through an exemplary process of producing a whole annotation from scratch. The exemplary annotation that is
used is conform to the [complete example](http://www.w3.org/TR/2014/WD-annotation-model-20141211/#complete-example) that
is shown at the end of an older version of the [Web Annotation Data Model specification](http://www.w3.org/TR/annotation-model/). We adopted some of the values to newer namings, although most of the old ones are still supported as deprecated methods.

The first step is to create an annotation using the *.createObject* method of the Anno4j object, which will be typed accordingly 
(via the relationship *rdf:type* as an *oa:Annotation*) on its own:

```java
    Anno4j anno4j = new Anno4j();
    
    // Create the base annotation
    Annotation annotation = anno4j.createObject(Annotation.class);
```

Then, provenance information is supported for the annotation. The timestamps *oa:annotatedAt* and *oa:serializedAt* are
supported by simply filling the members of the Annotation class:

```java
	// formerly annotatedAt
    annotation.setCreated("2014-09-28T12:00:00Z");
    // formerly serializedAt
    annotation.setGenerated("2013-02-04T12:00:00Z");
```

The motivation is defined by creating a new *Motvation* object, which is then added to the annotation. For Motivation objects, the *MotivationFactory* is to be used, which supports various functions for the different Motivation types:

```java
    Motivation commenting = MotivationFactory.getCommenting(anno4j);
    annotation.addMotivation(commenting);
```

As the annotation is created by a human being, the provenance feature of an *Agent*, in this case a *foaf:Person*, is utilized.
A *Person* object is created, filled with respective information, and then added to the annotation by setting the *creator*
field (formerly the *annotatedBy*) via the *.setCreator()*-method. All of this corresponds to the *agent1* entity of the example, which is connected to the annotation via the relationship
*oa:annotatedBy*.

```java
    // Create the person agent for the annotation
    Person person = anno4j.createObject(Person.class);
    person.setName("A. Person");
    person.setOpenID("http://example.org/agent1/openID1");
    
    annotation.setCreator(person);
```

In this example, the annotator made use of a homepage to create the annotation. This is implemented by a *prov:SoftwareAgent*
(corresponding to *agent2* in the example), which is also created and then added to the annotation via the field *generator*
(relationship *as:generator* in the RDF graph).

```java
    // Create the software agent for the annotation
    Software software = anno4j.createObject(Software.class);
    software.setName("Code v2.1");
    software.setHomepage("http://example.org/agent2/homepage1");

    annotation.setGenerator(software);
```

The next step contains the actual content of the annotation, aka the body (bottom left side of the example picture). **Disclaimer**: At this point, the new draft of the WADM supports the *TextualBody*, which already contains most of the fields needed to support the desired information. However, for explanatory reasons, the following example is kept as it is.

As the example is a text annotation, it is typed being a *oa:EmbeddedContent* with the property *dc:format* and the value *"text/plain"*. The
text of the annotation is supported via the *rdf:value* property of the body node, its language is specified by the
property *dc:language*.

In Anno4j, all this is done by specifying an own body interface (extending the interface
*Body*). The type of the body is supported in the first line (@Iri("http://www.w3.org/ns/oa#EmbeddedContent")) as a Java-annotation,
the respective attributes are defined using the *@Iri* Java-annotation above the respective setter **and** getter methods.
See the documentation of the class [here](anno4j-core/src/test/java/com/github/anno4j/example/TextAnnotationBody.java).

```java
    @Iri("http://www.w3.org/ns/oa#EmbeddedContent")
    public interface TextAnnotationBody extends Body {
    
        @Iri(DC.FORMAT)  
        String getFormat();
        
        @Iri(DC.FORMAT)
        void setFormat(String format);

        @Iri(RDF.VALUE)
        String getValue();

        @Iri(RDF.VALUE)
        void setValue(String value);
        
        @Iri(DC.LANGUAGE)
        String getLanguage();

        @Iri(DC.LANGUAGE)
        void setLanguage(String language);
    }
```

An instance of this class is then created, filled accordingly, and added to the annotation as body:

```java
    // Create the body
    TextAnnotationBody body = anno4j.createObject(TextAnnotationBody.class);
    body.setFormat("text/plain");
    body.setValue("One of my favourite cities");
    body.setLanguage("en");

    // Adding the body to the annotation object
    annotation.setBody(body);
```

The last thing that has to be added is the target (bottom right side of the picture), the "thing" that the annotation is about. In this case, the target
is specified in a more detailed fashion, as a subpart rather than the whole media item is to be selected. This requirement is
implemented by a combination of specific resource and a selector. A specific resource (which has the *rdf:type* *oa:SpecificResource*)
is an entity, that joins the actual target with its selector, and therefore the case that only a subpart of the whole media item is selected. The subpart represents a fragment, spatial and/or temporal part of the given multimedia item. In the example, a *oa:TextPositionSelector* selects a part of the
text that is annotated by supporting a start- (property *oa:start*) and end position (property *oa:end*). Lastly,
the actual target is connected with the specific resource node via the *oa:hasSource* relationship.

In Anno4j, a specific resource and a selector has to be created and then joined accordingly:

```java
    // Create the specific resource
    SpecificResource specificResource = anno4j.creatObject(SpecificResource.class);

	// Create the selector
    TextPositionSelector textPositionSelector = anno4j.createObject(TextPositionSelector.class);
    textPositionSelector.setStart(4096);
    textPositionSelector.setEnd(4104);

    specificResource.setSelector(textPositionSelector);
```

Afterwards, a target is created and then joined with the specific resource node. The last step connects the annotation with
the target. As the target is not specified any further in the example, we make use of a simple resource URI entity (interface *ResourceObject*).

```java
    // Create the actual target
    ResourceObject source = anno4j.createObject(ResourceObject.class);
    source.setResourceAsString("http://example.org/source1");
    
    specificResource.setSource(source);

    annotation.setTarget(specificResource);
```

The whole example implementation can be seen [here](anno4j-core/src/test/java/com/github/anno4j/example/ExampleTest.java).

## Status of Anno4j and the implemented WADM specification

The current version of Anno4j supports the most current draft of the Web Annotation Data Model, found at <https://www.w3.org/TR/2016/CR-annotation-model-20160705/>.

## LDPath restrictions

The following selectors are supported for LDPath:
 
**Property Selections**

A path definition selecting the value of a property. Either a URI enclosed in <> or a namespace prefix and a local name separated by :

    <URI> | PREFIX:LOCAL


**Path Traversal**

Traverse a path by following several edges in the RDF graph. Each step is separated by a /.

    PATH / PATH

**Unions**

Several alternative paths can be merged by using a union | between path elements

    PATH | PATH

**Groupings**

Path expressions can be grouped to change precedence or to improve readability by including them in braces:

    ( PATH )

**Value Testing**

The values of selections can be tested and filtered by adding test conditions in square brackets [] after a path selection:

    PATH [TEST]

The current version of Anno4j supports the following value tests:
 
***is-a Test***. 

Tests for a specific rdf:value.

    PATH[is-a VALUE]

***Literal language Test***

Literal language tests allow to select literal values of only the specified language. They can be expressed by @ followed 
by the ISO language tag or the special value none to select literals without language definition: `@LANGUAGE`, 
where LANGUAGE is the ISO language tag.

    "rdfs:label[@de]"
    
***Literal Type Test***

Literal type tests allow to select only literals of a specified type, e.g. to ensure that only decimal values are indexed: `^^TYPE`, 
where TYPE is the XML Schema type to select.

    "rdf:value[^^xsd:decimal]"
    
***Resource Path Value Tests***

Resource path value tests only allow resources where a subpath selection matches a certain value condition: `PATH is VALUE`, 
where PATH is an arbitrary path selection and VALUE is a URI, prefix:local, or literal value definition.

    "foaf:interest[rdf:type is ex:Food]"
    
***Test Conjunction and Disjunction***

Several tests can be connected using & (for conjunction/and) or | (for disjunction/or).

Select all interests of type ex:Food or type ex:Drink:

    "foaf:interest[rdf:type is ex:Food | rdf:type is ex:Drink]"

Select all interests of type ex:Food and type ex:Drink:

    "foaf:interest[rdf:type is ex:Food & rdf:type is ex:Drink]"
    
***Combinations of Tests***

A path traversal can contain several tests.

    "foaf:interest[rdf:type is ex:Food]/rdfs:label[@es]"
    

**Reverse Property Selections**

This is the reverse/inverse operation of the normal Property Selection.

Select all nodes connected to the current node via an incoming link, aka. go the specified link “backwards”:

    ^<URI>

**Recursive Selections**

Recursive selection will apply an selectore recursively. 

	(<SELECTOR>)*  //  zero-and-more 
	(<SELECTOR>)+  //  one-and-more 

**Intersections**

The intersection of several paths can be computed by using an intersection & between path elements: `PATH & PATH`. Where PATH is an arbitrary path selector.

    "foaf:interest & foaf:topic_interest"

## Development Guidelines

### Snapshot
Each push on the development branch triggers the build of a snapshot version. Snapshots are public available:
```
      <dependency>
        <groupId>com.github.anno4j</groupId>
        <artifactId>anno4j-core</artifactId>
        <version>X.X.X-SNAPSHOT</version>
      </dependency>
```     

### Compile, Package and Install

Package with:
```
      mvn package
```     

Install to your local repository
```
      mvn install
```     

### Participate
1. Create an issue
2. Fork Anno4j
3. Add features
4. Add jUnit Tests
5. Create pull request to anno4j/develop


### 3rd party integration of custom LDPath expressions

To contribute custom LDPath (test) functions, and thereby custom LDPath syntax the following two class has to be provided:

1. Step: 

Create a Java class that extends either the SelectorFunction class or the TestFunction class. This class defines the actual syntax
that has to be injected in to the Anno4j evaluation process.

```java
    public class GetSelector extends SelectorFunction<Node> {
    
        @Override
        protected String getLocalName() {
            return "getSelector";
        }
    
        @Override
        public Collection<Node> apply(RDFBackend<Node> backend, Node context, Collection<Node>... args) throws IllegalArgumentException {
            return null;
        }
    
        @Override
        public String getSignature() {
            return "fn:getSelector(Annotation) : Selector";
        }
    
        @Override
        public String getDescription() {
            return "Selects the Selector of a given annotation object.";
        }
    }

``` 

2. Step:

Create a Java class that actually evaluates the newly provided LDPath expression. This class needs
to be flagged with the @Evaluator Java annotation. The @Evaluator annotation requires the class 
of the description mentioned in the first step. Besides that, the evaluator has to implement either
the QueryEvaluator or the TestEvaluator interface. Inside the prepared evaluate method, the actual
SPARQL query has to be generated using the Apache Jena framework.

```java
    @Evaluator(GetSelector.class)
    public class GetSelectorFunctionEvaluator implements QueryEvaluator {
        @Override
        public Var evaluate(NodeSelector nodeSelector, ElementGroup elementGroup, Var var, LDPathEvaluatorConfiguration evaluatorConfiguration) {
            Var evaluate = new SelfSelectionEvaluator().evaluate(nodeSelector, elementGroup, var, evaluatorConfiguration);
            Var target = Var.alloc("target");
            Var selector = Var.alloc("selector");
    
            elementGroup.addTriplePattern(new Triple(evaluate.asNode(), new ResourceImpl(OADM.HAS_TARGET).asNode(), target));
            elementGroup.addTriplePattern(new Triple(target.asNode(), new ResourceImpl(OADM.HAS_SELECTOR).asNode(), selector));
            return selector;
        }
    }
``` 

## Contributors

- Kai Schlegel (University of Passau)
- Andreas Eisenkolb (University of Passau)
- Emanuel Berndl (University of Passau)
- Thomas Weißgerber (University of Passau)

> This software was partially developed within the [MICO project](http://www.mico-project.eu/) (Media in Context - European Commission 7th Framework Programme grant agreement no: 610480).

## License
 Apache License Version 2.0 - http://www.apache.org/licenses/LICENSE-2.0
 