/*
 * oxTrust is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2014, Gluu
 */

package org.gluu.oxtrust.model.scim2.provider;

import org.gluu.oxtrust.model.scim2.annotations.Attribute;
import org.gluu.oxtrust.model.scim2.AttributeDefinition;

/**
 * Holds the mapping of the schema extension characteristics for the resource type representation.
 *
 * @author Val Pecaoco
 * updated by jgomer2001 on 2017-09-23
 */
public class SchemaExtensionHolder {

    @Attribute(description = "The URI of an extended schema, e.g., \"urn:edu:2.0:Staff\". This MUST be equal to the \"id\" " +
            "attribute of a \"Schema\" resource.",
            isRequired = true,
            mutability = AttributeDefinition.Mutability.READ_ONLY)
    private String schema;

    @Attribute(description = "A Boolean value that specifies whether or not the schema extension is required for the resource type.",
            isRequired = true,
            mutability = AttributeDefinition.Mutability.READ_ONLY)
    private boolean required;

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public boolean getRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }
}
