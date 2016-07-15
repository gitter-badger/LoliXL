package org.to2mbn.lolixl.ui.container.presenter;

import javafx.fxml.FXMLLoader;
import javafx.scene.layout.BorderPane;
import org.to2mbn.lolixl.ui.api.ViewInitializer;
import org.to2mbn.lolixl.ui.container.view.DefaultUserProfileView;
import org.to2mbn.lolixl.utils.LazyReference;

import java.net.URL;

public class DefaultUserProfilePresenter implements ViewInitializer {
	public final LazyReference<DefaultUserProfileView> view = new LazyReference<>();
	public final LazyReference<BorderPane> root = new LazyReference<>();

	@Override
	public void initialize(URL fxmlLocation) {
		view.set(new FXMLLoader(fxmlLocation).getController());
		root.set(view.get().rootContainer);
	}
}