package lu.fisch.canze.actors;

import java.util.Collection;
import java.util.HashMap;

import lu.fisch.canze.activities.MainActivity;
import lu.fisch.canze.interfaces.FieldListener;
import lu.fisch.canze.interfaces.VirtualFieldAction;

/**
 * A field computed from other fields.
 *
 * It listens to its dependencies only while somebody listens to it. The old removeListener
 * removed itself from its own list instead of from the dependencies, so a virtual field never let
 * go of them. Adding, removing and attaching share one lock, so a listener added during a
 * removal can never end up on a detached field.
 *
 * Created by robertfisch on 15.11.2015.
 */
public class VirtualField extends Field implements FieldListener {

    private final HashMap<String, Field> dependantFields;
    protected volatile VirtualFieldAction virtualFieldAction = null;

    private final Object attachLock = new Object();
    private boolean attached = false;

    public VirtualField(String responseId, HashMap<String, Field> dependantFields, String unit, VirtualFieldAction virtualFieldAction) {
        // frame 800, bits 24-31, resolution 1, no decimals, no offset, generic car
        super("", Frames.getInstance().getById(0x800), (short) 24, (short) 31, 1, 0, 0, unit, responseId, (short) 0, null, null);
        this.dependantFields = dependantFields == null ? new HashMap<String, Field>() : dependantFields;
        this.virtual = true;
        // attached before the action is set, as before: the immediate callback each dependency
        // fires on registration must not compute a value yet
        synchronized (attachLock) {
            attachLocked();
        }
        this.virtualFieldAction = virtualFieldAction;
    }

    @Override
    public void onFieldUpdateEvent(Field field) {
        VirtualFieldAction action = virtualFieldAction;
        if (action == null) return;
        double computed;
        try {
            computed = action.updateValue(dependantFields);
        } catch (RuntimeException e) {
            MainActivity.debug("VirtualField " + getSID() + ": could not compute: " + e);
            return;
        }
        setValue(computed);
    }

    @Override
    public void addListener(FieldListener fieldListener) {
        if (fieldListener == null) return;
        synchronized (attachLock) {
            attachLocked();
            super.addListener(fieldListener);
        }
    }

    @Override
    public void removeListener(FieldListener fieldListener) {
        if (fieldListener == null) return;
        synchronized (attachLock) {
            super.removeListener(fieldListener);
            if (fieldListeners.isEmpty()) detachLocked();
        }
    }

    private void attachLocked() {
        if (attached) return;
        attached = true;
        for (Field field : dependantFields.values()) {
            if (field != null) field.addListener(this);
        }
    }

    private void detachLocked() {
        if (!attached) return;
        attached = false;
        for (Field field : dependantFields.values()) {
            if (field != null) field.removeListener(this);
        }
    }

    public Collection<Field> getFields() {
        return dependantFields.values();
    }
}
