/*
 * Copyright (C) 2024 B3Partners B.V.
 *
 * SPDX-License-Identifier: MIT
 */

package org.tailormap.api.security.events;

import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

public class OAuth2AuthenticationFailureEvent extends AbstractAuthenticationFailureEvent {

  public OAuth2AuthenticationFailureEvent(Authentication authentication, AuthenticationException exception) {
    super(authentication, exception);
  }
}
