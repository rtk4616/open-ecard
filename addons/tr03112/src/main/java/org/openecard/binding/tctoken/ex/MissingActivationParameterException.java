/****************************************************************************
 * Copyright (C) 2014 ecsec GmbH.
 * All rights reserved.
 * Contact: ecsec GmbH (info@ecsec.de)
 *
 * This file is part of the Open eCard App.
 *
 * GNU General Public License Usage
 * This file may be used under the terms of the GNU General Public
 * License version 3.0 as published by the Free Software Foundation
 * and appearing in the file LICENSE.GPL included in the packaging of
 * this file. Please review the following information to ensure the
 * GNU General Public License version 3.0 requirements will be met:
 * http://www.gnu.org/copyleft/gpl.html.
 *
 * Other Usage
 * Alternatively, this file may be used in accordance with the terms
 * and conditions contained in a signed written agreement between
 * you and ecsec GmbH.
 *
 ***************************************************************************/

package org.openecard.binding.tctoken.ex;

import org.openecard.addon.bind.BindingResult;
import org.openecard.addon.bind.BindingResultCode;
import org.openecard.common.I18nKey;


/**
 * Exception indication that the activation request is malformed.
 *
 * @author Tobias Wich
 */
public class MissingActivationParameterException extends FatalActivationError {

    public MissingActivationParameterException(String msg) {
	super(new BindingResult(BindingResultCode.MISSING_PARAMETER), msg);
    }

    public MissingActivationParameterException(String msg, Throwable ex) {
	super(new BindingResult(BindingResultCode.MISSING_PARAMETER), msg, ex);
    }

    public MissingActivationParameterException(I18nKey key, Object... params) {
	super(new BindingResult(BindingResultCode.MISSING_PARAMETER), key, params);
    }

    public MissingActivationParameterException(I18nKey key, Throwable cause, Object... params) {
	super(new BindingResult(BindingResultCode.MISSING_PARAMETER), key, cause, params);
    }

}
