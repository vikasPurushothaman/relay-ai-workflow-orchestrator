package com.relay.workflow;

import com.networknt.schema.*;
import com.networknt.schema.dialect.Dialects;
import com.networknt.schema.path.NodePath;
import com.networknt.schema.path.PathType;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Admission and runtime validation use the same local-only Draft2020-12 policy. */
@Component
public final class OutputSchemaValidator {
    private static final String DIALECT="https://json-schema.org/draft/2020-12/schema";
    private static final Set<String> MAPS=Set.of("$defs","properties","patternProperties","dependentSchemas");
    private static final Set<String> ARRAYS=Set.of("allOf","anyOf","oneOf","prefixItems");
    private static final Set<String> SINGLE=Set.of("not","if","then","else","items","contains","additionalProperties","propertyNames","unevaluatedItems","unevaluatedProperties","contentSchema");
    private static final Set<String> VOCAB=Set.of("core","applicator","unevaluated","validation","meta-data","format-annotation","content");
    private final SchemaRegistry registry=SchemaRegistry.withDialect(Dialects.getDraft202012(), builder -> builder
        .schemaCacheEnabled(false)
        .schemaLoader(loader -> loader.fetchRemoteResources(false))
        .schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(false).preloadSchema(false).build()));
    public void validate(JsonNode source,String path) {
        try {
            var locations=new ArrayList<NodePath>();
            inspect(source,path,new NodePath(PathType.JSON_POINTER),locations);
            var meta=registry.getSchema(SchemaLocation.of(DIALECT));
            if(!meta.validate(source).isEmpty()) throw new DefinitionException("invalid_output_schema",path);
            var compiled=registry.getSchema(source.deepCopy());
            var queue=new ArrayDeque<Schema>();
            queue.add(compiled);
            for(NodePath location:locations) if(location.getNameCount()>0) queue.add(compiled.getSubSchema(location));
            Set<JsonNode> visited=Collections.newSetFromMap(new IdentityHashMap<>());
            var checked=new ArrayList<Schema>();
            while(!queue.isEmpty()) {
                var current=queue.remove();
                if(!visited.add(current.getSchemaNode())) continue;
                // A local reference can point into an otherwise annotation-only object.
                // Once referenced, that object must obey the same schema policy.
                var nested=new ArrayList<NodePath>();
                inspect(current.getSchemaNode(),path,new NodePath(PathType.JSON_POINTER),nested);
                if(!meta.validate(current.getSchemaNode()).isEmpty()) throw new DefinitionException("invalid_output_schema",path);
                for(NodePath location:nested) if(location.getNameCount()>0) {
                    NodePath absolute=current.getSchemaLocation().getFragment();
                    for(int i=0;i<location.getNameCount();i++) {
                        Object element=location.getElement(i);
                        absolute=element instanceof Number ? absolute.append(((Number)element).intValue()) : absolute.append(element.toString());
                    }
                    queue.add(current.findSchemaResourceRoot().getSubSchema(absolute));
                }
                for(String key:List.of("$ref","$dynamicRef")) if(current.getSchemaNode().has(key))
                    queue.add(current.getRefSchema(SchemaLocation.Fragment.of(current.getSchemaNode().get(key).asString().substring(1))));
                checked.add(current);
            }
            for(Schema schema:checked) schema.initializeValidators();
        } catch(DefinitionException e) { throw e; }
        catch(RuntimeException | StackOverflowError e) { throw new DefinitionException("invalid_output_schema",path); }
    }
    public boolean accepts(JsonNode schema,JsonNode output) {
        try {
            validate(schema,"$.output_schema");
            return registry.getSchema(schema.deepCopy()).validate(output).isEmpty();
        }catch(RuntimeException | StackOverflowError ex){return false;}
    }
    private void inspect(JsonNode schema,String path,NodePath location,List<NodePath> locations) {
        locations.add(location);
        if(!schema.isObject()) return; // Boolean schemas and malformed shapes handled by the meta-schema.
        if(schema.has("$schema") && (!schema.get("$schema").isString() || !DIALECT.equals(schema.get("$schema").asString())))
            throw new DefinitionException("unsupported_schema_dialect",path);
        for(String keyword:List.of("$ref","$dynamicRef")) if(schema.has(keyword)) {
            var ref=schema.get(keyword);
            if(!ref.isString() || !ref.asString().startsWith("#")) throw new DefinitionException("unsupported_schema_reference",path);
        }
        if(schema.has("$vocabulary") && schema.get("$vocabulary").isObject()) {
            for(var property:schema.get("$vocabulary").properties()) {
                if(property.getValue().asBoolean() && VOCAB.stream().noneMatch(v -> property.getKey().equals("https://json-schema.org/draft/2020-12/vocab/"+v)))
                    throw new DefinitionException("unsupported_schema_vocabulary",path);
            }
        }
        // Traverse schema-bearing keywords only: examples/const/default are arbitrary data.
        for(String key:MAPS) if(schema.has(key) && schema.get(key).isObject())
            for(var child:schema.get(key).properties()) inspect(child.getValue(),path,location.append(key).append(child.getKey()),locations);
        for(String key:ARRAYS) if(schema.has(key) && schema.get(key).isArray()) {
            int i=0;
            for(JsonNode child:schema.get(key)) inspect(child,path,location.append(key).append(i++),locations);
        }
        for(String key:SINGLE) if(schema.has(key)) inspect(schema.get(key),path,location.append(key),locations);
    }
}
