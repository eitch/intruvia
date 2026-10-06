// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest.session;

import jakarta.ws.rs.NameBinding;
import java.lang.annotation.*;

@NameBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface ViewerRead {}
