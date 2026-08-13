package org.oagi.score.gateway.http.api.activity_management.annotation;

import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityHandler;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Supplies the default activity handler for annotated methods on a service class. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface ScoreActivityHandlerBinding {

    Class<? extends ScoreActivityHandler> value();
}
