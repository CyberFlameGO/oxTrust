/*
 * oxTrust is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2017, Gluu
 */
package org.gluu.oxtrust.service.antlr.scimFilter;

import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gluu.oxtrust.model.scim2.AttributeDefinition.Type;
import org.gluu.oxtrust.model.scim2.BaseScimResource;
import org.gluu.oxtrust.model.scim2.annotations.Attribute;
import org.gluu.oxtrust.model.scim2.extensions.ExtensionField;
import org.gluu.oxtrust.model.scim2.util.IntrospectUtil;
import org.gluu.oxtrust.service.antlr.scimFilter.antlr4.ScimFilterBaseListener;
import org.gluu.oxtrust.service.antlr.scimFilter.antlr4.ScimFilterParser;
import org.gluu.oxtrust.service.antlr.scimFilter.enums.CompValueType;
import org.gluu.oxtrust.service.antlr.scimFilter.enums.ScimOperator;
import org.gluu.oxtrust.service.antlr.scimFilter.util.FilterUtil;
import org.gluu.oxtrust.service.scim2.ExtensionService;
import org.gluu.search.filter.Filter;
import org.gluu.service.cdi.util.CdiUtil;
import org.gluu.util.Pair;

import javax.lang.model.type.NullType;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * Created by jgomer on 2017-12-09.
 */
public class FilterListener extends ScimFilterBaseListener {

    private Logger log = LogManager.getLogger(getClass());
    private Deque<Filter> filter;
    private Class<? extends BaseScimResource> resourceClass;
    private String error;
    private SubFilterGenerator subFilterGenerator;
    private ExtensionService extService;

    public FilterListener(Class<? extends BaseScimResource> resourceClass, boolean ldapBackend) {
        filter = new ArrayDeque<>();
        extService = CdiUtil.bean(ExtensionService.class);
        this.resourceClass = resourceClass;

        subFilterGenerator =  new SubFilterGenerator(ldapBackend);
    }

    @Override
    public void enterAttrexp(ScimFilterParser.AttrexpContext ctx) {

        if (StringUtils.isEmpty(error)) {
            log.trace("enterAttrexp.");

            String path = ctx.attrpath().getText();
            ScimFilterParser.CompvalueContext compValueCtx = ctx.compvalue();
            boolean isPrRule = compValueCtx == null && ctx.getChild(1).getText().equals("pr");

            Type attrType = null;
            Attribute attrAnnot = IntrospectUtil.getFieldAnnotation(path, resourceClass, Attribute.class);
            String ldapAttribute = null;
            boolean isNested = false;
            boolean multiValued = false;

            if (attrAnnot == null) {
                ExtensionField field = extService.getFieldOfExtendedAttribute(resourceClass, path);

                if (field == null) {
                    error = String.format("Attribute path '%s' is not recognized in %s", path, resourceClass.getSimpleName());
                } else {
                    attrType = field.getAttributeDefinitionType();
                    multiValued = field.isMultiValued();
                    ldapAttribute = path.substring(path.lastIndexOf(":") + 1);
                }
            } else {
                attrType = attrAnnot.type();
                multiValued = attrAnnot.multiValueClass().equals(NullType.class);
                Pair<String, Boolean> pair = FilterUtil.getLdapAttributeOfResourceAttribute(path, resourceClass);
                ldapAttribute = pair.getFirst();
                isNested = pair.getSecond();
            }

            if (error != null) {
                ;   //Intentionally left empty
            } else if (attrType == null) {
                error = String.format("Could not determine type of attribute path '%s' in %s", path, resourceClass.getSimpleName());
            } else if (ldapAttribute == null) {
                error = String.format("Could not determine LDAP attribute for path '%s' in %s", path, resourceClass.getSimpleName());
            } else {
                String subattr = isNested ? path.substring(path.lastIndexOf(".") + 1) : null;
                CompValueType type;
                ScimOperator operator;

                if (isPrRule) {
                    type = CompValueType.NULL;
                    operator = ScimOperator.NOT_EQUAL;
                } else {
                    type = FilterUtil.getCompValueType(compValueCtx);
                    operator = ScimOperator.getByValue(ctx.compareop().getText());
                }

                error = FilterUtil.checkFilterConsistency(path, attrType, type, operator);
                if (error == null) {
                    Pair<Filter, String> subf = subFilterGenerator
                            .build(subattr, ldapAttribute, isPrRule ? null : compValueCtx.getText(), attrType, type, operator, multiValued);
                    Filter subFilth = subf.getFirst();
                    error = subf.getSecond();

                    if (subFilth == null) {
                        if (error == null) {
                            error = String.format("Operator '%s' is not supported for attribute %s", operator.getValue(), path);
                        }
                    } else {
                        filter.push(subFilth);
                    }
                }
            }
        }
    }

    @Override
    public void exitAndFilter(ScimFilterParser.AndFilterContext ctx) {
        filter.push(Filter.createANDFilter(filter.poll(), filter.poll()));
    }

    @Override
    public void exitNegatedFilter(ScimFilterParser.NegatedFilterContext ctx) {
        if (ctx.getText().startsWith("not(")) {
            filter.push(Filter.createNOTFilter(filter.poll()));
        }
    }

    @Override
    public void exitOrFilter(ScimFilterParser.OrFilterContext ctx) {
        filter.push(Filter.createORFilter(filter.poll(), filter.poll()));
    }

    public String getError() {
        return error;
    }

    public Filter getFilter() {
        if (StringUtils.isEmpty(error)) {
            Filter f = filter.poll();
            log.info("LDAP filter expression computed was {}", Optional.ofNullable(f).map(Filter::toString).orElse(null));
            return f;
        }
        return null;
    }

}
