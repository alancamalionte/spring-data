package com.arangodb.springframework.core.repository.query.derived;

import com.arangodb.springframework.core.mapping.ArangoMappingContext;
import com.arangodb.springframework.core.mapping.ArangoPersistentProperty;
import com.arangodb.springframework.core.repository.query.ArangoParameterAccessor;
import com.arangodb.springframework.core.repository.query.derived.geo.Ring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.domain.Sort;
import org.springframework.data.geo.*;
import org.springframework.data.repository.query.parser.AbstractQueryCreator;
import org.springframework.data.repository.query.parser.Part;
import org.springframework.data.repository.query.parser.PartTree;
import org.springframework.util.Assert;

import java.util.*;

/**
 * Created by F625633 on 24/07/2017.
 */
public class DerivedQueryCreator extends AbstractQueryCreator<String, ConjunctionBuilder> {

    private static final Logger LOGGER = LoggerFactory.getLogger(DerivedQueryCreator.class);
    private static final Set<Part.Type> UNSUPPORTED_IGNORE_CASE = new HashSet<>();

    static {
        UNSUPPORTED_IGNORE_CASE.add(Part.Type.EXISTS);
        UNSUPPORTED_IGNORE_CASE.add(Part.Type.TRUE);
        UNSUPPORTED_IGNORE_CASE.add(Part.Type.FALSE);
        UNSUPPORTED_IGNORE_CASE.add(Part.Type.IS_NULL);
        UNSUPPORTED_IGNORE_CASE.add(Part.Type.IS_NOT_NULL);
        UNSUPPORTED_IGNORE_CASE.add(Part.Type.NEAR);
        UNSUPPORTED_IGNORE_CASE.add(Part.Type.WITHIN);
    }

    private final DisjunctionBuilder disjunctionBuilder = new DisjunctionBuilder(this);
    private final ArangoMappingContext context;
    private final String collectionName;
    private final PartTree tree;
    private final Map<String, Object> bindVars;
    private final ArangoParameterAccessor accessor;
    private final List<String> geoFields;
    private final boolean useFunctions;
    private Point uniquePoint = null;
    private String uniqueLocation = null;
    private Boolean isUnique = null;
    private int bindingCounter = 0;

    public DerivedQueryCreator(ArangoMappingContext context, Class<?> domainClass, PartTree tree,
            ArangoParameterAccessor accessor, Map<String, Object> bindVars, List<String> geoFields, boolean useFunctions) {
        super(tree, accessor);
        this.context = context;
        this.collectionName = context.getPersistentEntity(domainClass).getCollection();
        this.tree = tree;
        this.bindVars = bindVars;
        this.accessor = accessor;
        this.geoFields = geoFields;
        this.useFunctions = useFunctions;
    }

    public String getCollectionName() {
        return collectionName;
    }

    public List<String> getGeoFields() { return geoFields; }

    public double[] getUniquePoint() {
        if (uniquePoint == null) return new double[2];
        return new double[] {uniquePoint.getY(), uniquePoint.getX()};
    }

    @Override
    protected ConjunctionBuilder create(Part part, Iterator<Object> iterator) {
        return and(part, new ConjunctionBuilder(), iterator);
    }

    @Override
    protected ConjunctionBuilder and(Part part, ConjunctionBuilder base, Iterator<Object> iterator) {
        PartInformation partInformation = createPartInformation(part, iterator);
        if (partInformation != null) { base.add(partInformation); }
        return base;
    }

    @Override
    protected ConjunctionBuilder or(ConjunctionBuilder base, ConjunctionBuilder criteria) {
        disjunctionBuilder.add(base.build());
        return criteria;
    }

    @Override
    protected String complete(ConjunctionBuilder criteria, Sort sort) {
        if (tree.isDistinct() && !tree.isCountProjection()) LOGGER.debug(
                "Use of 'Distinct' is meaningful only in count queries");
        disjunctionBuilder.add(criteria.build());
        Disjunction disjunction = disjunctionBuilder.build();
        String array = disjunction.getArray().length() == 0 ? collectionName : disjunction.getArray();
        String predicate = disjunction.getPredicate().length() == 0 ? "" : " FILTER " + disjunction.getPredicate();
        String queryTemplate = "FOR e IN %s%s%s%s%s%s%s"; // collection predicate count sort limit pageable queryType
        String count = (tree.isCountProjection() || tree.isExistsProjection()) ? ((tree.isDistinct() ? " COLLECT entity = e" : "")
                + " COLLECT WITH COUNT INTO length") : "";
        String limit = tree.isLimiting() ? String.format(" LIMIT %d", tree.getMaxResults()) : "";
        String pageable = accessor.getPageable() == null ? "" : String.format(" LIMIT %d, %d",
                accessor.getPageable().getOffset(), accessor.getPageable().getPageSize());
        String geoFields = String.format("%s[0], %s[1]", uniqueLocation, uniqueLocation);
        String distanceAdjusted = getGeoFields().isEmpty() ? "e" : String.format(
                "MERGE(e, { '_distance': distance(%s, %f, %f) })", geoFields, getUniquePoint()[0], getUniquePoint()[1]);
        String type = tree.isDelete() ? (" REMOVE e IN " + collectionName)
                : ((tree.isCountProjection() || tree.isExistsProjection()) ? " RETURN length"
                        : String.format(" RETURN %s", distanceAdjusted));
        String sortString = buildSortString(sort);
        if ((!this.geoFields.isEmpty() || isUnique != null && isUnique)
                && !tree.isDelete() && !tree.isCountProjection() && !tree.isExistsProjection()) {
            String distanceSortKey = String.format(" SORT distance(%s, %f, %f)",
                    geoFields, getUniquePoint()[0], getUniquePoint()[1]);
            if (sortString.length() == 0) { sortString = distanceSortKey; }
            else { sortString = distanceSortKey + ", " + sortString.substring(5, sortString.length()); }
        }
        return String.format(queryTemplate, array, predicate, count, sortString, limit, pageable, type);
    }

    public static String buildSortString(Sort sort) {
        if (sort == null) LOGGER.debug("Sort in findAll(Sort) is null");
        StringBuilder sortBuilder = new StringBuilder(sort == null ? "" : " SORT");
        if (sort != null) for (Sort.Order order : sort) sortBuilder.append(
                (sortBuilder.length() == 5 ? " " : ", ") + "e."	+ order.getProperty() + " "	+ order.getDirection()
        );
        return sortBuilder.toString();
    }

    private String escapeSpecialCharacters(String string) {
        StringBuilder escaped = new StringBuilder();
        for (char character : string.toCharArray()) {
            if (character == '%' || character == '_' || character == '\\') escaped.append('\\');
            escaped.append(character);
        }
        return escaped.toString();
    }

    private String ignorePropertyCase(Part part) {
        String property = getProperty(part);
        if (!shouldIgnoreCase(part)) return property;
        if (!part.getProperty().getLeafProperty().isCollection()) return "LOWER(" + property + ")";
        return String.format("(FOR i IN TO_ARRAY(%s) RETURN LOWER(i))", property);
    }

    private String getProperty(Part part) {
        return "e." + context.getPersistentPropertyPath(part.getProperty()).toPath(null,
                ArangoPersistentProperty::getFieldName);
    }

    private Object ignoreArgumentCase(Object argument, boolean shouldIgnoreCase) {
        if (!shouldIgnoreCase) return argument;
        if (argument instanceof String) return ((String) argument).toLowerCase();
        List<String> lowered = new LinkedList<>();
        if (argument.getClass().isArray()) {
            String[] array = (String[]) argument;
            for (String string : array) lowered.add(string.toLowerCase());
        } else {
            Iterable<String> iterable = (Iterable<String>) argument;
            for (Object object : iterable) lowered.add(((String) object).toLowerCase());
        }
        return lowered;
    }

    private boolean shouldIgnoreCase(Part part) {
        Class<?> propertyClass = part.getProperty().getLeafProperty().getType();
        boolean isLowerable = String.class.isAssignableFrom(propertyClass);
        boolean shouldIgnoreCase = part.shouldIgnoreCase() != Part.IgnoreCaseType.NEVER && isLowerable
                && !UNSUPPORTED_IGNORE_CASE.contains(part.getType());
        if (part.shouldIgnoreCase() == Part.IgnoreCaseType.ALWAYS
                && (!isLowerable || UNSUPPORTED_IGNORE_CASE.contains(part.getType())))
            LOGGER.debug("Ignoring case for \"{}\" type is meaningless", propertyClass);
        return shouldIgnoreCase;
    }

    private ArgumentProcessingResult bindArguments(Iterator<Object> iterator, boolean shouldIgnoreCase, int arguments, Boolean borderStatus,
                              boolean ignoreBindVars) {
        int bindings = 0;
        ArgumentProcessingResult.Type type = ArgumentProcessingResult.Type.DEFAULT;
        for (int i = 0; i < arguments; ++i) {
            Assert.isTrue(iterator.hasNext(), "Too few arguments passed");
            Object caseAdjusted = ignoreArgumentCase(iterator.next(), shouldIgnoreCase);
            if (caseAdjusted.getClass() == Polygon.class) {
                type = ArgumentProcessingResult.Type.POLYGON;
                Polygon polygon = (Polygon) caseAdjusted;
                List<List<Double>> points = new LinkedList<>();
                polygon.forEach(p -> {
                    List<Double> point = new LinkedList<>();
                    point.add(p.getY());
                    point.add(p.getX());
                    points.add(point);
                });
                bindVars.put(Integer.toString(bindingCounter + bindings++), points);
                break;
            } else if (caseAdjusted.getClass() == Ring.class) {
                type = ArgumentProcessingResult.Type.RANGE;
                Point point = ((Ring) caseAdjusted).getPoint();
                checkUniquePoint(point);
                bindVars.put(Integer.toString(bindingCounter + bindings++), point.getY());
                bindVars.put(Integer.toString(bindingCounter + bindings++), point.getX());
                Range range = ((Ring) caseAdjusted).getRange();
                bindings = bindRange(range, bindings);
                break;
            } else if (caseAdjusted.getClass() == Box.class) {
                type = ArgumentProcessingResult.Type.BOX;
                Box box = (Box) caseAdjusted;
                Point first = box.getFirst();
                Point second = box.getSecond();
                double minLatitude = Math.min(first.getY(), second.getY());
                double maxLatitude = Math.max(first.getY(), second.getY());
                double minLongitude = Math.min(first.getX(), second.getX());
                double maxLongitude = Math.max(first.getX(), second.getX());
                bindVars.put(Integer.toString(bindingCounter + bindings++), minLatitude);
                bindVars.put(Integer.toString(bindingCounter + bindings++), maxLatitude);
                bindVars.put(Integer.toString(bindingCounter + bindings++), minLongitude);
                bindVars.put(Integer.toString(bindingCounter + bindings++), maxLongitude);
                break;
            } else if (caseAdjusted.getClass() == Circle.class) {
                Circle circle = (Circle) caseAdjusted;
                checkUniquePoint(circle.getCenter());
                bindVars.put(Integer.toString(bindingCounter + bindings++), circle.getCenter().getY());
                bindVars.put(Integer.toString(bindingCounter + bindings++), circle.getCenter().getX());
                bindVars.put(Integer.toString(bindingCounter + bindings++), convertDistanceToMeters(circle.getRadius()));
                break;
            } else if (caseAdjusted.getClass() == Point.class) {
                Point point = (Point) caseAdjusted;
                checkUniquePoint(point);
                if (ignoreBindVars) { continue; }
                bindVars.put(Integer.toString(bindingCounter + bindings++), point.getY());
                bindVars.put(Integer.toString(bindingCounter + bindings++), point.getX());
            } else if (caseAdjusted.getClass() == Distance.class) {
                Distance distance = (Distance) caseAdjusted;
                bindVars.put(Integer.toString(bindingCounter + bindings++), convertDistanceToMeters(distance));
            } else if (caseAdjusted.getClass() == Range.class) {
                type = ArgumentProcessingResult.Type.RANGE;
                Range range = (Range) caseAdjusted;
                bindings = bindRange(range, bindings);
            } else if (borderStatus != null && borderStatus) {
                String string = (String) caseAdjusted;
                bindVars.put(Integer.toString(bindingCounter + bindings++), escapeSpecialCharacters(string) + "%");
            } else if (borderStatus != null) {
                String string = (String) caseAdjusted;
                bindVars.put(Integer.toString(bindingCounter + bindings++), "%" + escapeSpecialCharacters(string));
            } else {
                bindVars.put(Integer.toString(bindingCounter + bindings++), caseAdjusted);
            }
        }
        return new ArgumentProcessingResult(type, bindings);
    }

    private void checkUniquePoint(Point point) {
        isUnique = (uniquePoint == null || uniquePoint.equals(point)) ? isUnique : false;
        if (!geoFields.isEmpty()) {
            Assert.isTrue(uniquePoint == null || uniquePoint.equals(point),
                    "Different Points are used - Distance is ambiguous");
            uniquePoint = point;
        }
    }

    private int bindRange(Range range, int bindings) {
        Object lowerBound = range.getLowerBound();
        Object upperBound = range.getUpperBound();
        if (lowerBound.getClass() == Distance.class && upperBound.getClass() == lowerBound.getClass()) {
            lowerBound = convertDistanceToMeters((Distance) lowerBound);
            upperBound = convertDistanceToMeters((Distance) upperBound);
        }
        bindVars.put(Integer.toString(bindingCounter + bindings++), lowerBound);
        bindVars.put(Integer.toString(bindingCounter + bindings++), upperBound);
        return bindings;
    }

    private double convertDistanceToMeters(Distance distance) {
        return distance.getNormalizedValue() * Metrics.KILOMETERS.getMultiplier() * 1000;
    }

    private void checkUniqueLocation(Part part) {
        isUnique = isUnique == null ? true : isUnique;
        isUnique = (uniqueLocation == null || uniqueLocation.equals(ignorePropertyCase(part))) ? isUnique : false;
        uniqueLocation = ignorePropertyCase(part);
    }

    private PartInformation createPartInformation(Part part, Iterator<Object> iterator) {
        boolean isArray = false;
        String clause = null;
        int arguments = 0;
        Boolean borderStatus = null;
        boolean ignoreBindVars = false;
        //TODO possibly refactor in the future if the complexity of this block does not increase
        switch (part.getType()) {
            case SIMPLE_PROPERTY:
                isArray = false;
                clause = String.format("%s == @%d", ignorePropertyCase(part), bindingCounter);
                arguments = 1;
                break;
            case NEGATING_SIMPLE_PROPERTY:
                isArray = false;
                clause = String.format("%s != @%d", ignorePropertyCase(part), bindingCounter);
                arguments = 1;
                break;
            case TRUE:
                isArray = false;
                clause = String.format("%s == true", ignorePropertyCase(part));
                break;
            case FALSE:
                isArray = false;
                clause = String.format("%s == false", ignorePropertyCase(part));
                break;
            case IS_NULL:
                isArray = false;
                clause = String.format("%s == null", ignorePropertyCase(part));
                break;
            case IS_NOT_NULL:
                isArray = false;
                clause = String.format("%s != null", ignorePropertyCase(part));
                break;
            case EXISTS:
                isArray = false;
                clause = String.format("HAS(e, '%s')", ignorePropertyCase(part).substring(2));
                break;
            case BEFORE:
            case LESS_THAN:
                isArray = false;
                clause = String.format("%s < @%d", ignorePropertyCase(part), bindingCounter);
                arguments = 1;
                break;
            case AFTER:
            case GREATER_THAN:
                isArray = false;
                clause = String.format("%s > @%d", ignorePropertyCase(part), bindingCounter);
                arguments = 1;
                break;
            case LESS_THAN_EQUAL:
                isArray = false;
                clause = String.format("%s <= @%d", ignorePropertyCase(part), bindingCounter);
                arguments = 1;
                break;
            case GREATER_THAN_EQUAL:
                isArray = false;
                clause = String.format("%s >= @%d", ignorePropertyCase(part), bindingCounter);
                arguments = 1;
                break;
            case BETWEEN:
                isArray = false;
                clause = String.format("@%d <= %s AND %s <= @%d", bindingCounter, ignorePropertyCase(part),
                        ignorePropertyCase(part), bindingCounter + 1);
                arguments = 2;
                break;
            case LIKE:
                isArray = false;
                clause = String.format("%s LIKE @%d", ignorePropertyCase(part), bindingCounter);
                arguments = 1;
                break;
            case NOT_LIKE:
                isArray = false;
                clause = String.format("NOT(%s LIKE @%d)", ignorePropertyCase(part), bindingCounter);
                arguments = 1;
                break;
            case STARTING_WITH:
                isArray = false;
                clause = String.format("%s LIKE @%d", ignorePropertyCase(part), bindingCounter);
                arguments = 1;
                borderStatus = true;
                break;
            case ENDING_WITH:isArray = false;
                clause = String.format("%s LIKE @%d", ignorePropertyCase(part), bindingCounter);
                arguments = 1;
                borderStatus = false;
                break;
            case REGEX:
                isArray = false;
                clause = String.format("REGEX_TEST(%s, @%d, %b)", ignorePropertyCase(part), bindingCounter,
                        shouldIgnoreCase(part));
                arguments = 1;
                break;
            case IN:
                isArray = false;
                clause = String.format("%s IN @%d", ignorePropertyCase(part), bindingCounter);
                arguments = 1;
                break;
            case NOT_IN:
                isArray = false;
                clause = String.format("%s NOT IN @%d", ignorePropertyCase(part), bindingCounter);
                arguments = 1;
                break;
            case CONTAINING:
                isArray = false;
                clause = String.format("@%d IN %s", bindingCounter, ignorePropertyCase(part));
                arguments = 1;
                break;
            case NEAR:
                checkUniqueLocation(part);
                if (useFunctions) {
                    isArray = true;
                    clause = String.format("NEAR(%s, @%d, @%d, COUNT(%s), '_distance')", collectionName,
                            bindingCounter, bindingCounter + 1, collectionName);
                    if (geoFields.isEmpty()) clause = unsetDistance(clause);
                } else {
                    ignoreBindVars = true;
                }
                arguments = 1;
                break;
            case WITHIN:
                checkUniqueLocation(part);
                if (useFunctions) {
                    isArray = true;
                    clause = String.format("WITHIN(%s, @%d, @%d, @%d, '_distance')", collectionName,
                            bindingCounter, bindingCounter + 1, bindingCounter + 2);
                    if (geoFields.isEmpty()) clause = unsetDistance(clause);
                } else {
                    isArray = false;
                    clause = String.format("distance(%s[0], %s[1], @%d, @%d) <= @%d",
                            ignorePropertyCase(part), ignorePropertyCase(part),
                            bindingCounter, bindingCounter + 1, bindingCounter + 2);
                }
                arguments = 2;
                break;
            default:
                Assert.isTrue(false, String.format("Part.Type \"%s\" not supported", part.getType().toString()));
                break;
        }
        if (!geoFields.isEmpty()) { Assert.isTrue(isUnique == null || isUnique,
                "Distance is ambiguous for multiple locations"); }
        ArgumentProcessingResult result = bindArguments(iterator, shouldIgnoreCase(part), arguments, borderStatus, ignoreBindVars);
        int bindings = result.bindings;
        switch (result.type) {
            case RANGE:
                checkUniqueLocation(part);
                if (useFunctions) {
                    clause = String.format(
                            "MINUS(WITHIN(%s, @%d, @%d, @%d, '_distance'), WITHIN(%s, @%d, @%d, @%d, '_distance'))",
                            collectionName, bindingCounter, bindingCounter + 1, bindingCounter + 3,
                            collectionName, bindingCounter, bindingCounter + 1, bindingCounter + 2);
                    if (geoFields.isEmpty()) clause = unsetDistance(clause);
                } else {
                    clause = String.format(
                            "@%d <= distance(%s[0], %s[1], @%d, @%d) AND distance(%s[0], %s[1], @%d, @%d) <= @%d",
                            bindingCounter + 2, ignorePropertyCase(part), ignorePropertyCase(part), bindingCounter,
                            bindingCounter + 1, ignorePropertyCase(part), ignorePropertyCase(part), bindingCounter,
                            bindingCounter + 1, bindingCounter + 3);
                }
                break;
            case BOX:
                clause = String.format("@%d <= %s[0] AND %s[0] <= @%d AND @%d <= %s[1] AND %s[1] <= @%d",
                        bindingCounter, ignorePropertyCase(part), ignorePropertyCase(part), bindingCounter + 1,
                        bindingCounter + 2, ignorePropertyCase(part), ignorePropertyCase(part), bindingCounter + 3);
                break;
            case POLYGON:
                clause = String.format("IS_IN_POLYGON(@%d, %s[0], %s[1])",
                        bindingCounter, ignorePropertyCase(part), ignorePropertyCase(part));
                break;
        }
        bindingCounter += bindings;
        return clause == null ? null : new PartInformation(isArray, clause);
    }

    private String unsetDistance(String clause) {
        return "(FOR e IN " + clause + " RETURN UNSET(e, '_distance'))";
    }

    private static class ArgumentProcessingResult {

        private final Type type;
        private final int bindings;

        public ArgumentProcessingResult(Type type, int bindings) {
            this.type = type;
            this.bindings = bindings;
        }

        private enum Type { DEFAULT, RANGE, BOX, POLYGON }
    }
}