/* HEADER */
package com.identity4j.util;

/*
 * #%L
 * Identity4J Utils
 * %%
 * Copyright (C) 2013 - 2017 LogonBox
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 * 
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */

import org.junit.Assert;
import org.junit.Test;

import com.identity4j.util.MultiMapException;

public class MultiMapExceptionTest {

    @Test
    public void messageConstructor() {
        String message = "Error!";
        try {
            throw new MultiMapException(message);
        } catch (MultiMapException mme) {
            Assert.assertEquals(mme.getMessage(), message);
        }
    }

    @Test
    public void messageAndCauseConstructor() {
        String message = "Error!";
        Throwable cause = new IllegalArgumentException();
        try {
            throw new MultiMapException(message, cause);
        } catch (MultiMapException mme) {
            Assert.assertEquals(mme.getMessage(), message);
            Assert.assertEquals(mme.getCause(), cause);
        }
    }
}