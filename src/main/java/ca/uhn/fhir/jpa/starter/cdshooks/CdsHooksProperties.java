package ca.uhn.fhir.jpa.starter.cdshooks;

public class CdsHooksProperties {

	private boolean enabled;

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	private String clientIdHeaderName;

	public String getClientIdHeaderName() {
		return clientIdHeaderName;
	}

	public void setClientIdHeaderName(String clientIdHeaderName) {
		this.clientIdHeaderName = clientIdHeaderName;
	}
}
