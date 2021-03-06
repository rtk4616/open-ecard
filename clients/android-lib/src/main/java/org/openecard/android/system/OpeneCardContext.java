/****************************************************************************
 * Copyright (C) 2017 ecsec GmbH.
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

package org.openecard.android.system;

import android.content.Context;
import org.openecard.addon.AddonManager;
import org.openecard.android.utils.ClasspathRegistry;
import org.openecard.android.ex.NfcDisabled;
import org.openecard.android.ex.NfcUnavailable;
import org.openecard.android.ex.UnableToInitialize;
import static org.openecard.android.system.ServiceConstants.*;
import static org.openecard.android.system.ServiceMessages.*;
import org.openecard.common.ClientEnv;
import org.openecard.common.ECardConstants;
import org.openecard.common.WSHelper;
import org.openecard.common.event.EventDispatcherImpl;
import org.openecard.common.event.EventObject;
import org.openecard.common.event.EventType;
import org.openecard.common.ifd.scio.TerminalFactory;
import org.openecard.common.interfaces.Dispatcher;
import org.openecard.common.interfaces.EventCallback;
import org.openecard.common.interfaces.EventDispatcher;
import org.openecard.common.sal.state.CardStateMap;
import org.openecard.common.sal.state.SALStateCallback;
import org.openecard.common.util.ByteUtils;
import org.openecard.gui.UserConsent;
import org.openecard.gui.android.AndroidUserConsent;
import org.openecard.ifd.protocol.pace.PACEProtocolFactory;
import org.openecard.ifd.scio.IFD;
import org.openecard.ifd.scio.IFDException;
import org.openecard.ifd.scio.IFDProperties;
import org.openecard.ifd.scio.wrapper.IFDTerminalFactory;
import org.openecard.management.TinyManagement;
import org.openecard.recognition.CardRecognitionImpl;
import org.openecard.sal.SelectorSAL;
import org.openecard.sal.TinySAL;
import org.openecard.scio.NFCFactory;
import org.openecard.transport.dispatcher.MessageDispatcher;
import org.openecard.ws.marshal.WsdefProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import iso.std.iso_iec._24727.tech.schema.ConnectionHandleType;
import iso.std.iso_iec._24727.tech.schema.EstablishContext;
import iso.std.iso_iec._24727.tech.schema.EstablishContextResponse;
import iso.std.iso_iec._24727.tech.schema.Initialize;
import iso.std.iso_iec._24727.tech.schema.ReleaseContext;
import iso.std.iso_iec._24727.tech.schema.Terminate;
import java.util.Arrays;
import java.util.List;
import org.openecard.android.ex.ApduExtLengthNotSupported;
import org.openecard.android.utils.NfcUtils;
import org.openecard.gui.android.EacNavigatorFactory;
import org.openecard.gui.android.InsertCardNavigatorFactory;
import org.openecard.gui.android.UserConsentNavigatorFactory;
import org.openecard.gui.definition.ViewController;


/**
 * Context object containing references to all internal objects of the Open eCard Stack.
 * This object can be obtained by either {@link OpeneCardServiceClient} or {@link OpeneCardServiceClientHandler}.
 * Instances of this class must not be used after the the Open eCard stack has been stopped.
 *
 * @author Mike Prechtl
 * @author Tobias Wich
 */
public class OpeneCardContext implements EventCallback {

    private static final Logger LOG = LoggerFactory.getLogger(OpeneCardContext.class);

    public static final String IFD_FACTORY_KEY = "org.openecard.ifd.scio.factory.impl";
    public static final String IFD_FACTORY_VALUE = "org.openecard.scio.NFCFactory";
    public static final String WSDEF_MARSHALLER_KEY = "org.openecard.ws.marshaller.impl";
    public static final String WSDEF_MARSHALLER_VALUE = "org.openecard.ws.android.AndroidMarshaller";


    private ClientEnv env;

    // Interface Device Layer (IFD)
    private IFD ifd;
    // Service Access Layer (SAL)
    private SelectorSAL sal;

    private AddonManager manager;
    private EventDispatcher eventDispatcher;
    private CardRecognitionImpl recognition;
    private CardStateMap cardStates;
    private Dispatcher dispatcher;
    private TerminalFactory terminalFactory;
    private TinyManagement management;

    private AndroidUserConsent gui;
    private EacNavigatorFactory eacNavFac;

    // true if already initialized
    private boolean initialized = false;
    // ContextHandle determines a specific IFD layer context
    private byte[] contextHandle;
    // true if card is available and usable
    private boolean isCardAvailable = false;
    // card type of the usable card
    private String cardType;

    private final Context appCtx;

    // package private so that only this package can use it
    OpeneCardContext(Context appCtx) {
	this.appCtx = appCtx;
    }


    ///
    /// Initialization & Shutdown
    ///

    void initialize() throws UnableToInitialize, NfcUnavailable, NfcDisabled, ApduExtLengthNotSupported {
	String errorMsg = SERVICE_RESPONSE_FAILED;

	if (initialized) {
	    throw new UnableToInitialize(SERVICE_ALREADY_INITIALIZED);
	}

	if (appCtx == null) {
	    throw new IllegalStateException(NO_APPLICATION_CONTEXT);
	}

	// initialize gui
	eacNavFac = new EacNavigatorFactory();
	List<UserConsentNavigatorFactory<?>> factories = Arrays.asList(
		eacNavFac,
		new InsertCardNavigatorFactory());

	gui = new AndroidUserConsent(factories);

	// set up nfc and android marshaller
	IFDProperties.setProperty(IFD_FACTORY_KEY, IFD_FACTORY_VALUE);
	WsdefProperties.setProperty(WSDEF_MARSHALLER_KEY, WSDEF_MARSHALLER_VALUE);
	NFCFactory.setContext(appCtx);

	try {
	    boolean nfcAvailable = NFCFactory.isNFCAvailable();
	    boolean nfcEnabled = NFCFactory.isNFCEnabled();
	    boolean nfcExtendedLengthSupport = NfcUtils.supportsExtendedLength(appCtx);
	    if (! nfcAvailable) {
		throw new NfcUnavailable();
	    } else if (! nfcEnabled) {
		throw new NfcDisabled();
	    } else if (! nfcExtendedLengthSupport) {
		throw new ApduExtLengthNotSupported(NFC_NO_EXTENDED_LENGTH_SUPPORT);
	    }
	    terminalFactory = IFDTerminalFactory.getInstance();
	    LOG.info("Terminal factory initialized.");
	} catch (IFDException ex) {
	    errorMsg = UNABLE_TO_INITIALIZE_TF;
	    throw new UnableToInitialize(errorMsg, ex);
	}

	try {
	    // set up client environment
	    env = new ClientEnv();

	    // set up dispatcher
	    dispatcher = new MessageDispatcher(env);
	    env.setDispatcher(dispatcher);
	    LOG.info("Message Dispatcher initialized.");

	    // set up management
	    management = new TinyManagement(env);
	    env.setManagement(management);
	    LOG.info("Management initialized.");

	    // set up event dispatcher
	    eventDispatcher = new EventDispatcherImpl();
	    env.setEventDispatcher(eventDispatcher);
	    LOG.info("Event Dispatcher initialized.");

	    // set up SALStateCallback
	    cardStates = new CardStateMap();
	    SALStateCallback salCallback = new SALStateCallback(env, cardStates);
	    eventDispatcher.add(salCallback);

	    // set up ifd
	    ifd = new IFD();
	    ifd.addProtocol(ECardConstants.Protocol.PACE, new PACEProtocolFactory());
	    ifd.setGUI(gui);
	    ifd.setEnvironment(env);
	    env.setIFD(ifd);
	    LOG.info("IFD initialized.");

	    // set up card recognition
	    try {
		recognition = new CardRecognitionImpl(env);
		recognition.setGUI(gui);
		env.setRecognition(recognition);
		LOG.info("CardRecognition initialized.");
	    } catch (Exception ex) {
		errorMsg = CARD_REC_INIT_FAILED;
		throw ex;
	    }

	    // set up SAL
	    TinySAL mainSAL = new TinySAL(env, cardStates);
	    mainSAL.setGUI(gui);

	    sal = new SelectorSAL(mainSAL, env);
	    env.setSAL(sal);
	    env.setCIFProvider(sal);
	    LOG.info("SAL initialized.");

	    ViewController viewController = new ViewController() {
		@Override
		public void showSettingsUI() { }

		@Override
		public void showDefaultViewUI() {}
	    };

	    // set up addon manager
	    try {
		manager = new AddonManager(env, gui, cardStates, viewController, new ClasspathRegistry());
		mainSAL.setAddonManager(manager);
	    } catch (Exception ex) {
		errorMsg = ADD_ON_INIT_FAILED;
		throw ex;
	    }

	    // Initialize the Event Dispatcher
	    eventDispatcher.add(this, EventType.TERMINAL_ADDED, EventType.TERMINAL_REMOVED,
		    EventType.CARD_INSERTED, EventType.CARD_RECOGNIZED, EventType.CARD_REMOVED);

	    // start event dispatcher
	    eventDispatcher.start();
	    LOG.info("Event dispatcher started.");

	    // initialize SAL
	    try {
		WSHelper.checkResult(sal.initialize(new Initialize()));
	    } catch (WSHelper.WSException ex) {
		errorMsg = ex.getMessage();
		throw ex;
	    }

	    // establish context
	    try {
		EstablishContext establishContext = new EstablishContext();
		EstablishContextResponse establishContextResponse = ifd.establishContext(establishContext);
		WSHelper.checkResult(establishContextResponse);
		contextHandle = establishContextResponse.getContextHandle();
		LOG.info("ContextHandle: {}", ByteUtils.toHexString(contextHandle));
	    } catch (WSHelper.WSException ex) {
		errorMsg = ESTABLISH_IFD_CONTEXT_FAILED;
		throw ex;
	    }

	    initialized = true;
	} catch (Exception ex) {
	    LOG.error(errorMsg, ex);
	    throw new UnableToInitialize(errorMsg, ex);
	}
    }

    String shutdown() {
	initialized = false;
	try {
	    if (ifd != null && contextHandle != null) {
		ReleaseContext releaseContext = new ReleaseContext();
		releaseContext.setContextHandle(contextHandle);
		ifd.releaseContext(releaseContext);
	    }
	    if (eventDispatcher != null) {
		eventDispatcher.terminate();
	    }
	    if (manager != null) {
		manager.shutdown();
	    }
	    if (sal != null) {
		Terminate terminate = new Terminate();
		sal.terminate(terminate);
	    }

	    return SUCCESS;
	} catch (Exception ex) {
	    LOG.error("Failed to terminate Open eCard instances...", ex);
	    return FAILURE;
	}
    }

    ///
    /// Recognize events
    ///

    @Override
    public void signalEvent(EventType eventType, EventObject o) {
	LOG.info("Event recognized: " + eventType.name());
	ConnectionHandleType ch = o.getHandle();
	switch (eventType) {
	    case CARD_RECOGNIZED:
		LOG.info("Card recognized.");
		if (ch != null && ch.getRecognitionInfo() != null) {
		    synchronized (OpeneCardContext.class) {
			cardType = ch.getRecognitionInfo().getCardType();
			isCardAvailable = true;
		    }
		    LOG.info("CardType: " + cardType);
		}
		break;
	    case CARD_INSERTED:
		LOG.info("Card inserted.");
		break;
	    case CARD_REMOVED:
		LOG.info("Card removed.");
		synchronized (OpeneCardContext.class) {
		    cardType = null;
		    isCardAvailable = false;
		}
		break;
	    case TERMINAL_ADDED:
		LOG.info("Terminal added.");
		break;
	    case TERMINAL_REMOVED:
		LOG.info("Terminal removed.");
		break;
	    default:
		break;
	}
    }



    ///
    /// Get-/Setter Methods
    ///

    public IFD getIFD() {
	return ifd;
    }

    public SelectorSAL getSAL() {
	return sal;
    }

    public EventDispatcher getEventDispatcher() {
	return eventDispatcher;
    }

    public byte[] getContextHandle() {
	return contextHandle;
    }

    public TinyManagement getTinyManagement() {
	return management;
    }

    public TerminalFactory getTerminalFactory() {
	return terminalFactory;
    }

    public Dispatcher getDispatcher() {
	return dispatcher;
    }

    public CardRecognitionImpl getRecognition() {
	return recognition;
    }

    public CardStateMap getCardStates() {
	return cardStates;
    }

    public ClientEnv getEnv() {
	return env;
    }

    public UserConsent getGUI() {
	return gui;
    }

    public EacNavigatorFactory getEacNavigatorFactory() {
	return eacNavFac;
    }

    public AddonManager getManager() {
	return manager;
    }

}
