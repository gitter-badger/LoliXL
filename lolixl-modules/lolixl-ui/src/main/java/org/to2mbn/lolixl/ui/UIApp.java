package org.to2mbn.lolixl.ui;

import javafx.application.Application;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.to2mbn.lolixl.ui.container.presenter.Presenter;
import org.to2mbn.lolixl.ui.container.view.DefaultFrameView;
import org.to2mbn.lolixl.ui.container.view.DefaultTitleBarView;
import org.to2mbn.lolixl.ui.container.view.DefaultUserProfileView;
import org.to2mbn.lolixl.utils.LazyReference;

import java.io.IOException;

@Component
@Service({UIPrimaryReferenceProvider.class})
public class UIApp extends Application implements UIPrimaryReferenceProvider {
	// TODO 这边的逗比设计以后一定改
	private final LazyReference<Stage> mainStage = new LazyReference<>();
	private final LazyReference<Scene> mainScene = new LazyReference<>();

	private static final String LOCATION_OF_FRAME = "/ui/fxml/container/default_frame.fxml";
	private static final String LOCATION_OF_TITLE_BAR = "/ui/fxml/container/default_title_bar.fxml";
	private static final String LOCATION_OF_USER_PROFILE = "/ui/fxml/container/default_user_profile.fxml";

	@Reference(target = "(presenter.name=default_frame_presenter)")
	private Presenter<DefaultFrameView, Parent> framePresenter;
	@Reference(target = "(presenter.name=default_title_bar_presenter)")
	private Presenter<DefaultTitleBarView, Parent> titleBarPresenter;
	@Reference(target = "(presenter.name=default_user_profile_presenter)")
	private Presenter<DefaultUserProfileView, Parent> userProfilePresenter;

	@Override
	public void start(Stage primaryStage) throws Exception {
		mainStage.set(primaryStage);
		mainStage.get().initStyle(StageStyle.UNDECORATED);

		resolvePresenters();
		mainScene.set(new Scene(framePresenter.content.get()));
		mainScene.get().getStylesheets().addAll("ui/css/metro.css", "ui/css/components.css");

		initLayout();
		mainStage.get().setScene(mainScene.get());
		mainStage.get().show();
	}

	private void resolvePresenters() throws IOException {
		framePresenter.initialize(getClass().getResource(LOCATION_OF_FRAME));
		titleBarPresenter.initialize(getClass().getResource(LOCATION_OF_TITLE_BAR));
		userProfilePresenter.initialize(getClass().getResource(LOCATION_OF_USER_PROFILE));
	}

	private void initLayout() {
		framePresenter.view.get().setTitleBar(titleBarPresenter.content.get());
		framePresenter.view.get().setWidget(userProfilePresenter.content.get());
	}

	@Override
	public void stop() throws Exception {
		// TODO
	}

	@Override
	public Stage getMainStage() {
		return mainStage.get();
	}

	@Override
	public Scene getMainScene() {
		return mainScene.get();
	}
}
