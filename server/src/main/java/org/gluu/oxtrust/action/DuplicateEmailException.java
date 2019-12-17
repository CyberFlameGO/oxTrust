package org.gluu.oxtrust.action;

public class DuplicateEmailException extends Exception {

	/**
	 * 
	 */
	private static final long serialVersionUID = -8140817165913364968L;

	public DuplicateEmailException(String message) {
		super(message);
	}

	@Override
	public String getMessage() {
		return "Email already used!";
	}

}
