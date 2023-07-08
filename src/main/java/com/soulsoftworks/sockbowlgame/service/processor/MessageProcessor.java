package com.soulsoftworks.sockbowlgame.service.processor;

import com.soulsoftworks.sockbowlgame.model.socket.in.SockbowlInMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;

import java.util.function.Function;

/**
 * This is an abstract class that provides a template for processing game messages.
 * The class uses a MessageProcessorMapping to map messages to the appropriate processing functions.
 */
public abstract class MessageProcessor {

    /**
     * The MessageProcessorMapping for this processor. Each subclass should initialize
     * its own MessageProcessorMapping.
     */
    protected final MessageProcessorMapping processorMapping;

    /**
     * Constructor. Initializes the processorMapping for this instance.
     */
    public MessageProcessor() {
        processorMapping = new MessageProcessorMapping();
        initializeProcessorMapping();
    }

    /**
     * Method to be overridden by subclasses to initialize their own processor mappings.
     */
    protected abstract void initializeProcessorMapping();

    /**
     * Processes a game message using the processor mapping.
     * This method is final and cannot be overridden by subclasses.
     *
     * @param message The message to process.
     *
     * @return Sockbowl Out Message
     */
    public final SockbowlOutMessage processMessage(SockbowlInMessage message) {
        // Get the function to process this message type
        Function<SockbowlInMessage, SockbowlOutMessage> func = processorMapping.getProcessor(message);

        // If a function was found, apply it
        if (func != null) {
            return func.apply(message);
        }

        return null;
    }
}
